/*
       Copyright 2017-2021 IBM Corp All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.trader;

import com.ibm.hybrid.cloud.sample.stocktrader.trader.client.BrokerClient;
import com.ibm.hybrid.cloud.sample.stocktrader.trader.json.Broker;

import com.ibm.cloud.objectstorage.ClientConfiguration;
import com.ibm.cloud.objectstorage.auth.AWSCredentials;
import com.ibm.cloud.objectstorage.auth.AWSStaticCredentialsProvider;
import com.ibm.cloud.objectstorage.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.ibm.cloud.objectstorage.oauth.BasicIBMOAuthCredentials;

//AWS S3 (wrapper for IBM Cloud Object Storage buckets)
import com.ibm.cloud.objectstorage.services.s3.AmazonS3;
import com.ibm.cloud.objectstorage.services.s3.AmazonS3ClientBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.HashMap;

//JSR 47 Logging
import java.util.logging.Logger;
import java.util.logging.Level;

//CDI 2.0
import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;

//Servlet 4.0
import javax.servlet.ServletException;
import javax.servlet.annotation.HttpConstraint;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.RequestDispatcher;

//mpConfig 1.3
import org.eclipse.microprofile.config.inject.ConfigProperty;

//mpMetrics 2.0
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;

//mpJWT 1.0
import org.eclipse.microprofile.jwt.JsonWebToken;
import com.ibm.websphere.security.openidconnect.PropagationHelper;

//mpRestClient 1.0
import org.eclipse.microprofile.rest.client.inject.RestClient;


/**
 * Servlet implementation class Summary
 */
@WebServlet(description = "Broker summary servlet", urlPatterns = { "/summary" })
@ServletSecurity(@HttpConstraint(rolesAllowed = { "StockTrader", "StockViewer" } ))
@ApplicationScoped
public class Summary extends HttpServlet {
	private static final long serialVersionUID = 4815162342L;
	private static final String EDITOR   = "StockTrader";
	private static final String LOGOUT   = "Log Out";
	private static final String CREATE   = "create";
	private static final String RETRIEVE = "retrieve";
	private static final String UPDATE   = "update";
	private static final String DELETE   = "delete";
	private static final String BASIC    = "basic";
	private static final String BRONZE   = "bronze";
	private static final String SILVER   = "silver";
	private static final String GOLD     = "gold";
	private static final String PLATINUM = "platinum";
	private static final String UNKNOWN  = "unknown";
	private static final String DOLLARS  = "USD";
	private static Logger logger = Logger.getLogger(Summary.class.getName());
	private static HashMap<String, Double> totals = new HashMap<String, Double>();
	private static HashMap<String, org.eclipse.microprofile.metrics.Gauge> gauges = new HashMap<String, org.eclipse.microprofile.metrics.Gauge>();
	private NumberFormat currency = null;
	private int basic=0, bronze=0, silver=0, gold=0, platinum=0, unknown=0; //loyalty level counts

	private @Inject @ConfigProperty(name = "TEST_MODE", defaultValue = "false") boolean testMode;
	private @Inject @RestClient BrokerClient brokerClient;
	private @Inject JsonWebToken jwt;
	private @Inject MetricRegistry metricRegistry;

	//used in the liveness probe
	public static boolean error = false;
	public static String message = null;

	private static boolean useS3 = false;
	private static AmazonS3 s3 = null;
	private static String s3Bucket = null;

	// Override Broker Client URL if config map is configured to provide URL
	static {
		useS3 = Boolean.parseBoolean(System.getenv("S3_ENABLED"));
		logger.info("useS3: "+useS3);

		String mpUrlPropName = BrokerClient.class.getName() + "/mp-rest/url";
		String brokerURL = System.getenv("BROKER_URL");
		if ((brokerURL != null) && !brokerURL.isEmpty()) {
			logger.info("Using Broker URL from config map: " + brokerURL);
			System.setProperty(mpUrlPropName, brokerURL);
		} else {
			logger.info("Broker URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
		}
	}

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public Summary() {
		super();

		currency = NumberFormat.getNumberInstance();
		currency.setMinimumFractionDigits(2);
		currency.setMaximumFractionDigits(2);
		currency.setRoundingMode(RoundingMode.HALF_UP);
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		String rows = null;

		try {
			rows = getTableRows(request);
			request.setAttribute("rows", rows);
		} catch (Throwable t) {
			logException(t);
			message = t.getMessage();
			error = true;
			request.setAttribute("message", message);
			request.setAttribute("error", error);
		}

		RequestDispatcher dispatcher = getServletContext().getRequestDispatcher("/WEB-INF/jsps/summary.jsp");
        dispatcher.forward(request, response);

	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String submit = request.getParameter("submit");

		if (submit != null) {
			if (submit.equals(LOGOUT)) {
				request.logout();

				HttpSession session = request.getSession();
				if (session != null) session.invalidate();

				response.sendRedirect("login");
			} else {
				String action = request.getParameter("action");
				String owner = request.getParameter("owner");

				if (action != null) {
					if (action.equals(CREATE)) {
						response.sendRedirect("addPortfolio"); //send control to the AddPortfolio servlet
					} else if (action.equals(RETRIEVE)) {
						response.sendRedirect("viewPortfolio?owner="+owner); //send control to the ViewPortfolio servlet
					} else if (action.equals(UPDATE)) {
						response.sendRedirect("addStock?owner="+owner+"&source=summary"); //send control to the AddStock servlet
					} else if (action.equals(DELETE)) {
//						PortfolioServices.deletePortfolio(request, owner);
						brokerClient.deleteBroker("Bearer "+getJWT(), owner);
						doGet(request, response); //refresh the Summary servlet
					} else {
						doGet(request, response); //something went wrong - just refresh the Summary servlet
					}
				} else {
					doGet(request, response); //something went wrong - just refresh the Summary servlet
				}
			}
		} else {
			doGet(request, response); //something went wrong - just refresh the Summary servlet
		}
	}

	private String getTableRows(HttpServletRequest request) {
		StringBuffer rows = new StringBuffer();

		if (brokerClient==null) {
			throw new NullPointerException("Injection of BrokerClient failed!");
		}

		if (jwt==null) {
			throw new NullPointerException("Injection of JWT failed!");
		}

//		JsonArray portfolios = PortfolioServices.getPortfolios(request);
		Broker[] brokers = testMode ? getHardcodedBrokers() : brokerClient.getBrokers("Bearer "+getJWT());
		
		// set brokers for JSP
		request.setAttribute("brokers", brokers);

		basic=0; bronze=0; silver=0; gold=0; platinum=0; unknown=0; //reset loyalty level counts
		metricRegistry.remove("portfolio_value");
		for (int index=0; index<brokers.length; index++) {
			Broker broker = brokers[index];
			String owner = broker.getOwner();

			if (useS3) logToS3(owner, broker);

			double total = broker.getTotal();
			String loyaltyLevel = broker.getLoyalty();

			setBrokerMetric(owner, total);
			if (loyaltyLevel!=null) {
				if (loyaltyLevel.equalsIgnoreCase(BASIC)) basic++;
				else if (loyaltyLevel.equalsIgnoreCase(BRONZE)) bronze++;
				else if (loyaltyLevel.equalsIgnoreCase(SILVER)) silver++;
				else if (loyaltyLevel.equalsIgnoreCase(GOLD)) gold++;
				else if (loyaltyLevel.equalsIgnoreCase(PLATINUM)) platinum++;
				else unknown++;
			}

    	}

		return rows.toString();
	}

	Broker[] getHardcodedBrokers() {
		Broker john = new Broker("John");
		john.setTotal(1234.56);
		john.setLoyalty("Basic");
		Broker karri = new Broker("Karri");
		karri.setTotal(12345.67);
		karri.setLoyalty("Bronze");
		Broker ryan = new Broker("Ryan");
		ryan.setTotal(23456.78);
		ryan.setLoyalty("Bronze");
		Broker raunak = new Broker("Raunak");
		raunak.setTotal(98765.43);
		raunak.setLoyalty("Silver");
		Broker greg = new Broker("Greg");
		greg.setTotal(123456.78);
		greg.setLoyalty("Gold");
		Broker eric = new Broker("Eric");
		eric.setTotal(1234567.89);
		eric.setLoyalty("Platinum");
		Broker[] brokers = { john, karri, ryan, raunak, greg, eric };
		return brokers;
	}

	void setBrokerMetric(String owner, double total) {
		totals.put(owner, total);
		if (gauges.get(owner)==null) try { //gauge not yet registered for this portfolio
			org.eclipse.microprofile.metrics.Gauge<Double> gauge = () -> { return totals.get(owner); };

			Metadata metadata = Metadata.builder().withName("broker_value").withType(MetricType.GAUGE).withUnit(DOLLARS).build();

			metricRegistry.register(metadata, gauge, new Tag("owner", owner)); //registry injected via CDI

			gauges.put(owner, gauge);
		} catch (Throwable t) {
			logger.warning(t.getMessage());
		}
	}

	@Gauge(name="broker_loyalty", tags="level=basic", displayName="Basic", unit=MetricUnits.NONE)
	public int getBasic() {
		return basic;
	}

	@Gauge(name="broker_loyalty", tags="level=bronze", displayName="Bronze", unit=MetricUnits.NONE)
	public int getBronze() {
		return bronze;
	}

	@Gauge(name="broker_loyalty", tags="level=silver", displayName="Silver", unit=MetricUnits.NONE)
	public int getSilver() {
		return silver;
	}

	@Gauge(name="broker_loyalty", tags="level=gold", displayName="Gold", unit=MetricUnits.NONE)
	public int getGold() {
		return gold;
	}

	@Gauge(name="broker_loyalty", tags="level=platinum", displayName="Platinum", unit=MetricUnits.NONE)
	public int getPlatinum() {
		return platinum;
	}

	@Gauge(name="broker_loyalty", tags="level=unknown", displayName="Unknown", unit=MetricUnits.NONE)
	public int getUnknown() {
		return unknown;
	}

	private String getJWT() {
		String token;
		if ("Bearer".equals(PropagationHelper.getAccessTokenType())) {
			token = PropagationHelper.getIdToken().getAccessToken();
			logger.fine("Retrieved JWT provided through oidcClientConnect feature");
		} else {
			token = jwt.getRawToken();
			logger.fine("Retrieved JWT provided through CDI injected JsonWebToken");
		}
		return token;
	}

	private void logToS3(String key, Object document) {
		try {
			if (s3 == null) {
				logger.info("Initializing S3");
				String region = System.getenv("S3_REGION");
				String apiKey = System.getenv("S3_API_KEY");
				String serviceInstanceId = System.getenv("S3_SERVICE_INSTANCE_ID");
				String endpointUrl = System.getenv("S3_ENDPOINT_URL");
				String location = System.getenv("S3_LOCATION");

				AWSCredentials credentials = new BasicIBMOAuthCredentials(apiKey, serviceInstanceId);
				ClientConfiguration clientConfig = new ClientConfiguration().withRequestTimeout(5000).withTcpKeepAlive(true);
				s3 = AmazonS3ClientBuilder.standard()
					.withCredentials(new AWSStaticCredentialsProvider(credentials))
					.withEndpointConfiguration(new EndpointConfiguration(endpointUrl, location))
					.withPathStyleAccessEnabled(true)
					.withClientConfiguration(clientConfig)
					.build();

				s3Bucket = System.getenv("S3_BUCKET");
				if (!s3.doesBucketExistV2(s3Bucket)) {
					logger.info("Creating S3 bucket: "+s3Bucket);
					s3.createBucket(s3Bucket);
				}
			}

			logger.fine("Putting object in S3 bucket for "+key);
			s3.putObject(s3Bucket, key, document.toString());
		} catch (Throwable t) {
			logException(t);
		}
	}

	static void logException(Throwable t) {
		logger.warning(t.getClass().getName()+": "+t.getMessage());

		//only log the stack trace if the level has been set to at least INFO
		if (logger.isLoggable(Level.INFO)) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			logger.info(writer.toString());
		}
	}
}
