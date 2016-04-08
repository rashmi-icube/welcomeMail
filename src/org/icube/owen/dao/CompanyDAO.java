package org.icube.owen.dao;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimerTask;

import javax.mail.MessagingException;

import org.icube.owen.email.EmailSender;
import org.icube.owen.helper.UtilHelper;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.REXPMismatchException;
import org.rosuda.REngine.Rserve.RConnection;

public class CompanyDAO extends TimerTask {
	private Connection myConn;
	private RConnection rCon;

	@Override
	public void run() {

		getCompanyDetails();

	}

	/**
	 * Retrieves the details of the company (ID,name,username,password,sql server,sql user id)
	 */
	public void getCompanyDetails() {
		ResultSet companyDetails = null;
		try {
			startDbConnection();

			Statement stmt = null;

			stmt = myConn.createStatement();
			companyDetails = stmt
					.executeQuery("Select comp_name, comp_id, comp_sql_dbname, sql_server, sql_user_id, sql_password from company_master");
			// connect to r
			rCon = (rCon != null && rCon.isConnected()) ? rCon : new RConnection();

			String rScriptPath = UtilHelper.getConfigProperty("r_script_path");
			String workingDir = "setwd(\"" + rScriptPath + "\")";
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("The working directory is:" + workingDir);
			rCon.eval(workingDir);
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Successfully connected to R:" + workingDir);
			int count = 0;
			while (companyDetails.next()) {

				// run JobInitStatus
				String s = "source(\"Job.R\")";
				REXP loadRScript = rCon.eval(s);
				if (loadRScript.inherits("try-error")) {
					org.apache.log4j.Logger.getLogger(CompanyDAO.class).error("Error: " + loadRScript.asString());
					throw new REXPMismatchException(loadRScript, "Error: " + loadRScript.asString());
				} else {
					org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Successfully loaded Job.R script " + loadRScript.asList());
				}
				org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("JobInitStatus method started");
				org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug(
						"Parameters for R function :  CompanyId : " + companyDetails.getInt("comp_id"));

				rCon.assign("CompanyId", new int[] { companyDetails.getInt("comp_id") });
				org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Calling the actual function in R Script JobInitStatus");
				REXP status = rCon.parseAndEval("try(eval(JobInitStatus(CompanyId)))");
				if (status.inherits("try-error")) {
					sendEmail(companyDetails.getInt("comp_id"), companyDetails.getString("comp_name"), status);
				} else {
					org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Successfully executed the JobInitStatus method ");
				}

				connectToCompanyDb(companyDetails);
				count++;
			}
			companyDetails.last();
			int size = companyDetails.getRow();
			System.out.println(size);
			if (count == size) {
				EmailSender es = new EmailSender();
				try {
					es.sendEmail("Successfully executed the Scheduler Job");
				} catch (MessagingException e) {
					org.apache.log4j.Logger.getLogger(CompanyDAO.class).error("Unable to send the Email", e);

				}
			}
		} catch (Exception e) {
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).error("Failed to get the db connection details", e);

		} finally {
			closeSqlDbConnection();
			closeRConnection();
		}
	}

	/**
	 * Connects to the company database and execute the methods
	 * @param rs - ResultSet containing the details of the company
	 * @throws SQLException 
	 */
	public void connectToCompanyDb(ResultSet rs) {

		String cUrl = "";
		Statement stmt = null;
		ResultSet res = null;
		try {
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Connecting to CompanyDb with ID:" + rs.getInt("comp_id"));
			cUrl = "jdbc:mysql://" + rs.getString("sql_server") + ":3306/" + rs.getString("comp_sql_dbname");
			myConn = DriverManager.getConnection(cUrl, rs.getString("sql_user_id"), rs.getString("sql_password"));
			org.apache.log4j.Logger.getLogger(CompanyDAO.class)
					.debug("Successfully connected to company db with companyId : " + rs.getInt("comp_id"));
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Starting query to retrieve number of questions closed");
			stmt = myConn.createStatement();
			java.util.Date date = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000);
			res = stmt.executeQuery("select count(que_id) as question_ended from question where end_date='" + date + "' and survey_batch_id=1;");
			res.next();
			int questionsClosed = res.getInt("question_ended");

			if (questionsClosed > 0) {

				int companyId = rs.getInt("comp_id");
				String companyName = rs.getString("comp_name");
				org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Started jobs for company: " + companyName);
				runRMethod("calculate_edge", companyId, companyName);
				runRMethod("update_neo", companyId, companyName);
				runRMethod("JobIndNwMetric", companyId, companyName);
				runRMethod("JobCubeNwMetric", companyId, companyName);
				runRMethod("JobDimensionNwMetric", companyId, companyName);
				runRMethod("JobIndividualMetric", companyId, companyName);
				runRMethod("JobTeamMetric", companyId, companyName);
				runRMethod("JobDimensionMetric", companyId, companyName);
				runRMethod("JobInitiativeMetric", companyId, companyName);
				runRMethod("JobAlert", companyId, companyName);
			}
		} catch (Exception e) {
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).error("Unable to connect to the Company db", e);
		}

	}

	public void runRMethod(String rFunctionName, int companyId, String companyName) throws Exception {
		org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Parameters for R function :  CompanyId : " + companyId);

		rCon.assign("CompanyId", new int[] { companyId });

		org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug(rFunctionName + " method started");
		REXP status = rCon.parseAndEval("try(eval(" + rFunctionName + "(CompanyId)))");
		if (status.inherits("try-error")) {
			sendEmail(companyId, companyName, status);
		} else {
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Successfully executed the " + rFunctionName + " method ");
		}

	}

	public void startDbConnection() throws Exception {

		// get db properties
		try {
			String user = UtilHelper.getConfigProperty("user");
			String password = UtilHelper.getConfigProperty("password");
			String dburl = UtilHelper.getConfigProperty("dburl");

			// connect to sql database
			myConn = DriverManager.getConnection(dburl, user, password);
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Successfully connected to the database");
		} catch (SQLException e) {
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).error("Unable to connect to database", e);
		}

	}

	public void closeSqlDbConnection() {
		try {
			if (!myConn.isClosed()) {
				myConn.close();
				org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Connection to mySql closed!!!!");
			}
		} catch (SQLException e) {
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).error("An error occurred while attempting to close SQL db connections", e);
		}
	}

	public void closeRConnection() {
		try {
			if (rCon.isConnected()) {
				rCon.close();
				org.apache.log4j.Logger.getLogger(CompanyDAO.class).debug("Connection to R closed!!!!");
			}
		} catch (Exception e) {
			org.apache.log4j.Logger.getLogger(CompanyDAO.class).error("An error occurred while attempting to close R connection", e);

		}
	}

	public void sendEmail(int companyId, String companyName, REXP status) throws Exception {
		EmailSender es = new EmailSender();
		es.sendEmail("Error in executing JobInitStatus method for Company Id:" + companyId + "Company Name:" + companyName);
		org.apache.log4j.Logger.getLogger(CompanyDAO.class).error("Error: " + status.asString());
		throw new Exception("Error: " + status.asString());
	}
}