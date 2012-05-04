/*
 * ERDDAP Copyright 2007, NOAA.
 * See the LICENSE.txt file in this file's directory.
 */
package gov.noaa.pfel.erddap;

import com.cohort.array.Attributes;
import com.cohort.array.DoubleArray;
import com.cohort.array.IntArray;
import com.cohort.array.PrimitiveArray;
import com.cohort.array.StringArray;
import com.cohort.util.Calendar2;
import com.cohort.util.File2;
import com.cohort.util.Math2;
import com.cohort.util.MustBe;
import com.cohort.util.ResourceBundle2;
import com.cohort.util.SimpleException;
import com.cohort.util.String2;
import com.cohort.util.Test;
import com.cohort.util.XML;

import gov.noaa.pfel.coastwatch.griddata.DataHelper;
import gov.noaa.pfel.coastwatch.griddata.Grid;
import gov.noaa.pfel.coastwatch.griddata.OpendapHelper;
import gov.noaa.pfel.coastwatch.pointdata.Table;
import gov.noaa.pfel.coastwatch.sgt.CompoundColorMap;
import gov.noaa.pfel.coastwatch.sgt.SgtMap;
import gov.noaa.pfel.coastwatch.sgt.SgtUtil;
import gov.noaa.pfel.coastwatch.util.SimpleXMLReader;
import gov.noaa.pfel.coastwatch.util.SSR;

import gov.noaa.pfel.erddap.dataset.*;
import gov.noaa.pfel.erddap.util.*;
import gov.noaa.pfel.erddap.variable.EDV;
import gov.noaa.pfel.erddap.variable.EDVGridAxis;
import gov.noaa.pfel.erddap.variable.EDVTimeGridAxis;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.verisign.joid.consumer.OpenIdFilter;

/**
 * ERDDAP is NOAA NMFS SWFSC ERD's Data Access Program, 
 * a Java servlet which serves gridded and tabular data
 * in common data file formats (e.g., ASCII, .dods, .mat, .nc)
 * and image file formats (e.g., .pdf and .png). 
 *
 * <p> This works like an OPeNDAP DAP-style server conforming to the
 * DAP 2.0 spec (see the Documentation section at www.opendap.org). 
 *
 * <p>The authentication method is set by the authentication tag in setup.xml.
 * See its use below and in EDStatic.
 *
 * <p>Authorization is specified by roles tags and accessibleTo tags in datasets.xml.
 * <br>If a user isn't authorized to use a dataset, then EDStatic.listPrivateDatasets 
 *    determines whether the dataset appears on lists of datasets (e.g., categorize or search).
 * <br>If a user isn't authorized to use a dataset and requests info about that
 *    dataset, EDStatic.redirectToLogin is called.
 * <br>These policies are enforced by checking edd.isAccessibleTo results from 
 *    gridDatasetHashMap and tableDatasetHashMap
 *    (notably also via gridDatasetIDs, tableDatasetIDs, allDatasetIDs).
 *
 * @author Bob Simons (bob.simons@noaa.gov) 2007-06-20
 */
public class Erddap extends HttpServlet {

    /** "ERROR" is defined here (from String2.ERROR) so that it is consistent in log files. */
    public final static String ERROR = String2.ERROR; 

    /**
     * Set this to true (by calling verbose=true in your program, 
     * not but changing the code here)
     * if you want lots of diagnostic messages sent to String2.log.
     */
    public static boolean verbose = false; 

    /**
     * Set this to true (by calling reallyVerbose=true in your program, 
     * not but changing the code here)
     * if you want lots and lots of diagnostic messages sent to String2.log.
     */
    public static boolean reallyVerbose = false; 

    /** This identifies the dods server/version that this mimics. */
    public static String dapVersion = "DAP/2.0";   //???
    public static String serverVersion = "dods/3.7"; //this is what thredds replies
      //drds at http://oceanwatch.pfeg.noaa.gov/opendap/GLOBEC/GLOBEC_bottle.ver replies "DODS/3.2"
      //both reply with server version, neither replies with coreVersion
      //spec says #.#.#, but Gallagher says #.# is fine.

    /** The programmatic/computer access to Erddap services are available as 
     * all of the plainFileTypes. 
     * All plainFileTypes must be valid EDDTable.dataFileTypeNames.
     * If added a new type, also add to sendPlainTable below and
     *  "//list of plainFileTypes" for rest.html.
     */
    public static String plainFileTypes[] = {
        //no need for .csvp or .tsvp, because plainFileTypes never write units
        ".csv", ".htmlTable", ".json", ".mat", ".nc", ".tsv", ".xhtml"};

    public final static String WMS_SERVER = "request"; //last part of url for actual wms server
    public final static int WMS_MAX_LAYERS = 16; //arbitrary
    public final static int WMS_MAX_WIDTH = 2048; //arbitrary
    public final static int WMS_MAX_HEIGHT = 2048; //arbitrary
    public final static char WMS_SEPARATOR = ':'; //separates datasetID and variable name (not a valid interior char)

    // ************** END OF STATIC VARIABLES *****************************

    protected RunLoadDatasets runLoadDatasets;
    public int todaysNRequests, totalNRequests;
    public String lastReportDate = "";

    /** Set by loadDatasets. */
    /** datasetHashMaps are read from many threads and written to by loadDatasets, 
     * so need to synchronize these maps.
     * grid/tableDatasetHashMap are key=datasetID value=edd.
     * [See Projects.testHashMaps() which shows that ConcurrentHashMap gives
     * me a thread-safe class without the time penalty of Collections.synchronizedMap(new HashMap()).]
     */
    public ConcurrentHashMap gridDatasetHashMap  = new ConcurrentHashMap(); 
    public ConcurrentHashMap tableDatasetHashMap = new ConcurrentHashMap(); 
    /** The RSS info: key=datasetId, value=utf8 byte[] of rss xml */
    public ConcurrentHashMap rssHashMap          = new ConcurrentHashMap(); 
    public ConcurrentHashMap failedLogins        = new ConcurrentHashMap(); 
    /** categoryInfo is not ConcurrentHashMap or synchronized map because
     * it is constructed in one thread (LoadDatasets) then put into place here
     * where it is read but never changed.
     */
    public Map categoryInfo = new HashMap(); 
    public long lastClearedFailedLogins = System.currentTimeMillis();


    /**
     * The constructor.
     *
     * <p> This needs to find the content/erddap directory.
     * It may be a defined environment variable ("erddapContent"),
     * but is usually a subdir of <tomcat> (e.g., usr/local/tomcat/content/erddap/).
     *
     * <p>This redirects logging messages to the log.txt file in bigParentDirectory 
     * (specified in <tomcat>/content/erddap/setup.xml) or to a CommonsLogging file.
     * This is appropriate for use as a web service. 
     * If you are using Erddap within a Java program and you want
     * to redirect diagnostic and error messages back to System.out, use
     * String2.setupLog(true, false, "", false, false, 1000000);
     * after calling this.
     *
     * @throws Throwable if trouble
     */
    public Erddap() throws Throwable {
        String2.log("\n\\\\\\\\**** Start Erddap constructor");
        long constructorMillis = System.currentTimeMillis();

        //make new catInfo with first level hashMaps
        int nCat = EDStatic.categoryAttributes.length;
        for (int cat = 0; cat < nCat; cat++) 
            categoryInfo.put(EDStatic.categoryAttributes[cat], new HashMap());

        //start RunLoadDatasets
        runLoadDatasets = new RunLoadDatasets(this);
        EDStatic.runningThreads.put("runLoadDatasets", runLoadDatasets); 
        runLoadDatasets.start(); 

        //done
        String2.log("\n\\\\\\\\**** Erddap constructor finished. TIME=" +
            (System.currentTimeMillis() - constructorMillis));
    }

    /**
     * destroy() is called by Tomcat whenever the servlet is removed from service.
     * See example at http://classes.eclab.byu.edu/462/demos/PrimeSearcher.java
     *
     * <p> Erddap doesn't overwrite HttpServlet.init(servletConfig), but it could if need be. 
     * runLoadDatasets is created by the Erddap constructor.
     */
    public void destroy() {
        EDStatic.destroy();
    }

    /**
     * This returns a StringArray with all the datasetIDs for all of the grid datasets.
     *
     * @param sorted if true, the resulting StringArray is sorted (ignoring case)
     * @return a StringArray with all the datasetIDs for all of the grid datasets.
     */
    public StringArray gridDatasetIDs(boolean sorted) {
        StringArray sa  = new StringArray(gridDatasetHashMap.keys()); 
        if (sorted) sa.sortIgnoreCase();
        return sa;
    }
    
    /**
     * This returns a StringArray with all the datasetIDs for all of the table datasets.
     *
     * @param sorted if true, the resulting StringArray is sorted (ignoring case)
     * @return a StringArray with all the datasetIDs for all of the table datasets.
     */
    public StringArray tableDatasetIDs(boolean sorted) {
        StringArray sa  = new StringArray(tableDatasetHashMap.keys()); 
        if (sorted) sa.sortIgnoreCase();
        return sa;
    }
    
    /**
     * This returns a StringArray with all the datasetIDs for all of the datasets.
     *
     * @param sorted if true, the resulting StringArray is sorted (ignoring case)
     * @return a StringArray with all the datasetIDs for all of the datasets.
     */
    public StringArray allDatasetIDs(boolean sorted) {
        StringArray sa  = new StringArray(gridDatasetHashMap.keys()); 
        sa.append(new StringArray(tableDatasetHashMap.keys()));
        if (sorted) sa.sortIgnoreCase();
        return sa;
    }
   

    /**
     * This returns the category values (sortIgnoreCase) for a given category attribute.
     * 
     * @param attribute e.g., "institution"
     * @return the category values for a given category attribute (or empty StringArray if none).
     */
    public StringArray categoryInfo(String attribute) {
        HashMap hm = (HashMap)categoryInfo.get(attribute);
        if (hm == null)
            return new StringArray();
        StringArray sa  = new StringArray(hm.keySet().iterator());
        sa.sortIgnoreCase();
        return sa;
    }
    
    /**
     * This returns the datasetIDs (sortIgnoreCase) for a given category value for a given category attribute.
     * 
     * @param attribute e.g., "institution"
     * @param value e.g., "NOAA_NDBC"
     * @return the datasetIDs for a given category value for a given category 
     *    attribute (or empty StringArray if none).
     */
    public StringArray categoryInfo(String attribute, String value) {
        HashMap hm = (HashMap)categoryInfo.get(attribute);
        if (hm == null)
            return new StringArray();
        HashSet hs = (HashSet)hm.get(value);
        if (hs == null)
            return new StringArray();
        StringArray sa  = new StringArray(hs.iterator());
        sa.sortIgnoreCase();
        return sa;
    }

    /**
     * This responds to a "post" request from the user by extending HttpServlet's doPost
     * and passing the request to doGet.
     *
     * @param request 
     * @param response
     */
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        doGet(request, response);
    }

    /** 
     * This responds to a "get" request from the user by extending HttpServlet's doGet.
     * Mostly, this just identifies the protocol (e.g., "tabledap") in the requestUrl
     * (right after the warName) and calls doGet&lt;Protocol&gt; to handle
     * the request. That allows Erddap to work like a DAP server, or a WCS server,
     * or a ....
     *
     * @param request
     * @param response
     * @throws ServletException, IOException
     */
    public void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {

        long doGetTime = System.currentTimeMillis();
        todaysNRequests++;
        int requestNumber = totalNRequests++;

        try {

            //get loggedInAs
            String loggedInAs = EDStatic.getLoggedInAs(request);
            EDStatic.tally.add("Requester Is Logged In (since startup)", "" + (loggedInAs != null));
            EDStatic.tally.add("Requester Is Logged In (since last daily report)", "" + (loggedInAs != null));

            //String2.log("requestURL=" + request.getRequestURL());
            String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
            String requestUrl = request.getRequestURI();  //post EDD.baseUrl, pre "?"

            //get requester's ip addresses (x-forwarded-for)
            //getRemoteHost(); returns our proxy server (never changes)
            //For privacy reasons, don't tally full individual IP address; the 4th ip number is removed.
            String ipAddress = request.getHeader("x-forwarded-for");  
            if (ipAddress == null) {
                ipAddress = "";
            } else {
                //if csv, get last part
                //see http://en.wikipedia.org/wiki/X-Forwarded-For
                int cPo = ipAddress.lastIndexOf(',');
                if (cPo >= 0)
                    ipAddress = ipAddress.substring(cPo + 1);
            }
            ipAddress = ipAddress.trim();
            if (ipAddress.length() == 0)
                ipAddress = "(unknownIPAddress)";

            //get userQuery
            String userQuery = request.getQueryString(); //may be null;  leave encoded
            if (userQuery == null)
                userQuery = "";
            String2.log("{{{{#" + requestNumber + " " +
                Calendar2.getCurrentISODateTimeStringLocal() + " " + 
                (loggedInAs == null? "(notLoggedIn)" : loggedInAs) + " " +
                ipAddress + " " +
                requestUrl + (userQuery.length() == 0? "" : "?" + userQuery));

            //Redirect to http if loggedInAs == null && non-login/logout request was sent to https.
            //loggedInAs is used in many, many places to determine if ERDDAP should use https for e.g., /images/... .
            //So if user isn't logged in, http is used and 
            //  that puts non-https content on https pages.
            //But browsers object to that.
            //This solution: redirect non-loggedIn users to http.
            if (loggedInAs == null &&
                (!requestUrl.equals("/erddap/login.html") && !requestUrl.equals("/erddap/logout.html"))) {
                String fullRequestUrl = request.getRequestURL().toString();
                if (fullRequestUrl.startsWith("https://")) {
                    //hopefully headers and other info won't be lost in the redirect
                    if (verbose) String2.log("Redirecting loggedInAs=null request from https: to http:.");
                    response.sendRedirect(EDStatic.baseUrl + requestUrl +
                        (userQuery.length() == 0? "" : "?" + userQuery));
                    return;            
                }
            }

            //Redirect to https if loggedInAs != null && request was sent to http.
            //logged in user on http: page appears not logged in.
            //(Also, she might accidentally forget to log out.)
            //loggedInAs is used in many, many places to determine if ERDDAP should use https for e.g., /images/... .
            //So if user is logged in, https is used and that puts https content on http pages.
            //This solution: redirect loggedIn users to https.
            if (loggedInAs != null) {
                String fullRequestUrl = request.getRequestURL().toString();
                if (fullRequestUrl.startsWith("http://")) {
                    //hopefully headers and other info won't be lost in the redirect
                    if (verbose) String2.log("Redirecting loggedInAs!=null request from http: to https:.");
                    response.sendRedirect(EDStatic.baseHttpsUrl + requestUrl +
                        (userQuery.length() == 0? "" : "?" + userQuery));
                    return;            
                }
            }

            //refuse request? e.g., to fend of a Denial of Service attack or an overzealous web robot
            int periodPo = ipAddress.lastIndexOf('.'); //to make #.#.#.* test below
            if (EDStatic.requestBlacklist != null &&
                (EDStatic.requestBlacklist.contains(ipAddress) ||
                 (periodPo >= 0 && EDStatic.requestBlacklist.contains(ipAddress.substring(0, periodPo+1) + "*")))) {
                //use full ipAddress, to help id user                //odd capitilization sorts better
                EDStatic.tally.add("Requester's IP Address (Blocked) (since last Major LoadDatasets)", ipAddress);
                EDStatic.tally.add("Requester's IP Address (Blocked) (since last daily report)", ipAddress);
                EDStatic.tally.add("Requester's IP Address (Blocked) (since startup)", ipAddress);
                String2.log("Requester is on the datasets.xml requestBlacklist.");
                response.sendError(HttpServletResponse.SC_FORBIDDEN, //a.k.a. Error 403
                    "Your IP address is on the request blacklist.  To be taken off of the blacklist, email " +
                    EDStatic.adminEmail + " .");
                return;
            }

            //tally ipAddress                                    //odd capitilization sorts better
            EDStatic.tally.add("Requester's IP Address (Allowed) (since last Major LoadDatasets)", ipAddress);
            EDStatic.tally.add("Requester's IP Address (Allowed) (since last daily report)", ipAddress);
            EDStatic.tally.add("Requester's IP Address (Allowed) (since startup)", ipAddress);

            //look for Chinese guy getting SODA data
            if (ipAddress.equals("121.106.212.91")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, //a.k.a. Error 403
                    "I see you want SODA data, but you ask for too much at once. Let's work together to find a better way. Email me: bob.simons@noaa.gov .");
                return;
            }

            //look for double trouble in query (since java version may be old)
            //Remove this some day in the future (2012?).
            if (String2.isDoubleTrouble(userQuery)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, //a.k.a. Error 403
                    "Oy!  Some numbers shouldn't be used in polite company!  Go bug some other web site.");
                return;
            }            


            //requestUrl should start with /erddap/
            //deal with /erddap
            //??? '\' on windows computers??? or '/' since it isn't a real directory?
            if (!requestUrl.startsWith("/" + EDStatic.warName + "/")) {
                response.sendRedirect(tErddapUrl + "/index.html");
                return;
            }
            int protocolStart = EDStatic.warName.length() + 2;            

            //get protocol (e.g., "griddap" or "tabledap")
            int protocolEnd = requestUrl.indexOf("/", protocolStart);
            if (protocolEnd < 0)
                protocolEnd = requestUrl.length();
            String protocol = requestUrl.substring(protocolStart, protocolEnd);
            String endOfRequest = requestUrl.substring(protocolStart);
            if (reallyVerbose) String2.log("  protocol=" + protocol);

            //Pass the query to the requested protocol or web page.
            //Be as restrictive as possible (so resourceNotFound can be caught below, if possible).
            if (protocol.equals("griddap") ||
                protocol.equals("tabledap")) {
                doDap(request, response, loggedInAs, protocol, protocolEnd + 1, userQuery);
            } else if (EDStatic.sosActive && protocol.equals("sos")) {
                doSos(request, response, loggedInAs, protocolEnd + 1, userQuery); 
            } else if (EDStatic.wcsActive && protocol.equals("wcs")) {
                doWcs(request, response, loggedInAs, protocolEnd + 1, userQuery); 
            } else if (protocol.equals("wms")) {
                doWms(request, response, loggedInAs, protocolEnd + 1, userQuery);
            } else if (endOfRequest.equals("") || endOfRequest.equals("index.htm")) {
                response.sendRedirect(tErddapUrl + "/index.html");
            } else if (protocol.startsWith("index.")) {
                doIndex(request, response, loggedInAs);
            } else if (protocol.equals("download") ||
                       protocol.equals("images") ||
                       protocol.equals("public")) {
                doTransfer(request, response, protocol, protocolEnd + 1);
            } else if (protocol.equals("rss")) {
                doRss(request, response, protocol, protocolEnd + 1);
            } else if (endOfRequest.startsWith("search/advanced.")) {  //before test for "search"
                doAdvancedSearch(request, response, loggedInAs, protocolEnd + 1, userQuery);
            } else if (protocol.equals("search")) {
                doSearch(request, response, loggedInAs, protocol, protocolEnd + 1, userQuery);
            } else if (protocol.equals("categorize")) {
                doCategorize(request, response, loggedInAs, protocol, protocolEnd + 1, userQuery);
            } else if (protocol.equals("info")) {
                doInfo(request, response, loggedInAs, protocol, protocolEnd + 1);
            } else if (endOfRequest.equals("information.html")) {
                doInformationHtml(request, response, loggedInAs);
            } else if (endOfRequest.equals("legal.html")) {
                doLegalHtml(request, response, loggedInAs);
            } else if (endOfRequest.equals("login.html")) {
                doLogin(request, response, loggedInAs);
            } else if (endOfRequest.equals("logout.html")) {
                doLogout(request, response, loggedInAs);
            } else if (endOfRequest.equals("rest.html")) {
                doRestHtml(request, response, loggedInAs);
            } else if (endOfRequest.equals("setDatasetFlag.txt")) {
                doSetDatasetFlag(request, response, userQuery);
            } else if (endOfRequest.equals("sitemap.xml")) {
                doSitemap(request, response);
            } else if (endOfRequest.equals("slidesorter.html")) {
                doSlideSorter(request, response, loggedInAs, userQuery);
            } else if (endOfRequest.equals("status.html")) {
                doStatus(request, response, loggedInAs);
            } else if (protocol.equals("subscriptions")) {
                doSubscriptions(request, response, loggedInAs, ipAddress, endOfRequest, 
                    protocol, protocolEnd + 1, userQuery);
            } else if (protocol.equals("convert")) {
                doConvert(request, response, loggedInAs, endOfRequest, 
                    protocolEnd + 1, userQuery);
            } else if (protocol.equals("post")) {
                doPostPages(request, response, loggedInAs, endOfRequest, 
                    protocolEnd + 1, userQuery);
            } else if (endOfRequest.equals("version")) {
                doVersion(request, response);
            } else {
                sendResourceNotFoundError(request, response, "");
            }
            
            //tally
            EDStatic.tally.add("Protocol (since startup)", protocol);
            EDStatic.tally.add("Protocol (since last daily report)", protocol);

            //clear the String2.logStringBuffer
            StringBuffer logSB = String2.getLogStringBuffer();
            if (logSB != null) 
                logSB.setLength(0); 

            long responseTime = System.currentTimeMillis() - doGetTime;
            String2.distribute(responseTime, EDStatic.responseTimesDistributionLoadDatasets);
            String2.distribute(responseTime, EDStatic.responseTimesDistribution24);
            String2.distribute(responseTime, EDStatic.responseTimesDistributionTotal);
            if (verbose) String2.log("}}}}#" + requestNumber + " SUCCESS. TIME=" + responseTime + "\n");

        } catch (Throwable t) {

            try {
                String message = MustBe.throwableToString(t);
                
                //Don't email common, unimportant exceptions   e.g., ClientAbortException
                //Are there others I don't need to see?
                if (message.indexOf("ClientAbortException") >= 0) {
                    String2.log("#" + requestNumber + " Error: ClientAbortException");

                } else if (message.indexOf(DataHelper.THERE_IS_NO_DATA) >= 0) {
                    String2.log("#" + requestNumber + " " + message);

                } else {
                    String q = request.getQueryString(); //not decoded
                    message = "#" + requestNumber + " Error for url=" + request.getRequestURI() +
                        (q == null || q.length() == 0? "" : "?" + q) + 
                        "\nerror=" + message;
                    String2.log(message);
                    if (reallyVerbose) //should this be just 'verbose', or a separate setting?
                        EDStatic.email(EDStatic.emailEverythingTo, 
                            String2.ERROR, 
                            message);
                }

                //"failure" includes clientAbort and there is no data
                long responseTime = System.currentTimeMillis() - doGetTime;
                String2.distribute(responseTime, EDStatic.failureTimesDistributionLoadDatasets);
                String2.distribute(responseTime, EDStatic.failureTimesDistribution24);
                String2.distribute(responseTime, EDStatic.failureTimesDistributionTotal);
                if (verbose) String2.log("}}}}#" + requestNumber + " FAILURE. TIME=" + responseTime + "\n");

                //???if "ClientAbortException", should ERDDAP not send error code below?
            } catch (Throwable t2) {
                String2.log("Error while handling error:\n" + MustBe.throwableToString(t2));
            }

            //if sendErrorCode fails because response.isCommitted(), it throws ServletException
            sendErrorCode(request, response, t); 
        }

    }

    /** 
     * This responds to an /erddap/index.xxx request
     *
     * @param request
     * @param response
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @throws ServletException, IOException
     */
    public void doIndex(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDD.baseUrl, pre "?"

        //plain file types  
        for (int pft = 0; pft < plainFileTypes.length; pft++) { 

            //index.pft  - return a list of resources
            if (requestUrl.equals("/" + EDStatic.warName + "/index" + plainFileTypes[pft])) {

                String fileTypeName = File2.getExtension(requestUrl);
                EDStatic.tally.add("Main Resources List (since startup)", fileTypeName);
                EDStatic.tally.add("Main Resources List (since last daily report)", fileTypeName);
                Table table = new Table();
                StringArray resourceCol = new StringArray();
                StringArray urlCol = new StringArray();
                table.addColumn("Resource", resourceCol);
                table.addColumn("URL", urlCol);
                StringArray resources = new StringArray(
                    new String[] {"info", "search", "categorize", "griddap", "tabledap"});
                if (EDStatic.sosActive) resources.add("sos");
                if (EDStatic.wcsActive) resources.add("wcs");
                resources.add("wms");
                for (int r = 0; r < resources.size(); r++) {
                    resourceCol.add(resources.get(r));
                    urlCol.add(tErddapUrl + "/" + resources.get(r) + "/index" + fileTypeName +
                        (resources.get(r).equals("search")? "?searchFor=" : ""));
                }
                sendPlainTable(loggedInAs, request, response, table, "Resources", fileTypeName);
                return;
            }
        }

        //only thing left should be erddap/index.html request
        if (!requestUrl.equals("/" + EDStatic.warName + "/index.html")) {
            sendResourceNotFoundError(request, response, "");
            return;
        }

        //display main erddap index.html page 
        EDStatic.tally.add("Home Page (since startup)", ".html");
        EDStatic.tally.add("Home Page (since last daily report)", ".html");
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Home Page", out); 
        try {
            //set up the table
            String tdString = " align=\"left\" valign=\"top\">\n";
            writer.write("<table width=\"100%\" border=\"0\" cellspacing=\"12\" cellpadding=\"0\">\n" +
                "<tr>\n<td width=\"60%\"" + tdString);


            //*** left column: theShortDescription
            String shortDescription = EDStatic.theShortDescriptionHtml(tErddapUrl);
            //special case for POST
            if (EDStatic.postShortDescriptionActive) 
                shortDescription = String2.replaceAll(shortDescription, 
                    "[standardPostDescriptionHtml]", getPostIndexHtml(loggedInAs, tErddapUrl));
            writer.write(shortDescription);
            shortDescription = null;

            //thin vertical line between text columns
            writer.write(
                "</td>\n" + 
                "<td class=\"verticalLine\"><br></td>\n" + //thin vertical line
                "<td" + tdString); //unspecified width will be the remainder

            //*** the right column: Get Started with ERDDAP
            writer.write(
                "<h2>" + EDStatic.getStartedHtml + "</h2>\n" +
                "<ul>");

            //display /info link with list of all datasets
            writer.write(
                "\n<li><h3><a href=\"" + tErddapUrl + "/info/index.html\">" +
                "View a List of All " + 
                (gridDatasetHashMap.size() + tableDatasetHashMap.size()) +
                " Datasets</a></h3>\n");

            //display a search form
            writer.write("\n<li>");
            writer.write(getSearchFormHtml(loggedInAs, "<h3>", "</h3>", ""));

            //display categorize options
            writer.write("\n<li>");
            writeCategorizeOptionsHtml1(loggedInAs, writer, null, true);

            //display Advanced Search option
            writer.write("\n<li><h3>Search for Datasets with " +
                getAdvancedSearchLink(loggedInAs, "") + "</h3>\n");

            //display protocol links
            String protSee = " title=\"" + EDStatic.protocolClick + " ";
            writer.write(
                "\n<li>" +
                "<h3>" + EDStatic.protocolSearchHtml + "</h3>\n" +
                EDStatic.protocolSearch2Html +
                "<br>Click on a protocol to see a list of datasets which are available via that protocol in ERDDAP." +
                "<br>&nbsp;\n" +
                "<table class=\"erd commonBGColor\" cellspacing=\"0\">\n" +
                "  <tr><th>Protocol</th><th>Description</th></tr>\n" +
                "  <tr>\n" +
                "    <td><a href=\"" + tErddapUrl + "/griddap/index.html\"" + 
                    protSee + "griddap protocol.\">griddap</a> </td>\n" +
                "    <td>" + EDStatic.EDDGridDapDescription + "</td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td><a href=\"" + tErddapUrl + "/tabledap/index.html\"" + 
                    protSee + "tabledap protocol.\">tabledap</a></td>\n" +
                "    <td>" + EDStatic.EDDTableDapDescription + "</td>\n" +
                "  </tr>\n");
            if (EDStatic.sosActive) writer.write(
                "  <tr>\n" +
                "    <td><a href=\"" + tErddapUrl + "/sos/index.html\"" + 
                    protSee + "SOS protocol.\">SOS</a></td>\n" +
                "    <td>" + EDStatic.sosDescriptionHtml + "</td>\n" +
                "  </tr>\n");
            if (EDStatic.wcsActive) writer.write(
                "  <tr>\n" +
                "    <td><a href=\"" + tErddapUrl + "/wcs/index.html\"" + 
                    protSee + "WCS protocol.\">WCS</a></td>\n" +
                "    <td>" + EDStatic.wcsDescriptionHtml + "</td>\n" +
                "  </tr>\n");
            writer.write(
                "  <tr>\n" +
                "    <td><a href=\"" + tErddapUrl + "/wms/index.html\"" + 
                    protSee + "WMS protocol.\">WMS</a></td>\n" +
                "    <td>" + EDStatic.wmsDescriptionHtml + "</td>\n" +
                "  </tr>\n" +
                "</table>\n");  

            //end of search/protocol options list
            writer.write("\n</ul>\n");

            //end of table
            writer.write("</td>\n</tr>\n</table>\n");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * This responds by sending out the "Information" Html page (EDStatic.theLongDescriptionHtml).
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     */
    public void doInformationHtml(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs) throws Throwable {        

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);        
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Information", out);
        try {
            writer.write(EDStatic.youAreHere(loggedInAs, "Information"));
            writer.write(EDStatic.theLongDescriptionHtml(tErddapUrl));
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * This responds by sending out the legal.html page (setup.xml <legal>).
     *
     */
    public void doLegalHtml(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs) throws Throwable {        

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);        
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Legal Notices", out);
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, "Legal Notices") +
                "<a href=\"#disclaimers\">Disclaimers</a> | " +
                "<a href=\"#privacyPolicy\">Privacy Policy</a> | " +
                "<a href=\"#dataLicenses\">Data Licenses</a> | " +
                "<a href=\"#contact\">Contact</a>\n" +
                "\n" +
                "<h2><a name=\"disclaimers\">Disclaimers</a></h2>\n" +
                "\n" +
                EDStatic.standardGeneralDisclaimer + "\n\n" +
                EDStatic.legal(tErddapUrl));

        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /** This is used by doLogin to add a failed login attempt to failedLogins */
    public void loginFailed(String user) {
        if (verbose) String2.log("loginFailed " + user);
        EDStatic.tally.add("Log in failed (since startup)", user);
        EDStatic.tally.add("Log in failed (since last daily report)", user);
        int ia[] = (int[])failedLogins.get(user);
        boolean wasNull = ia == null;
        if (wasNull)
            ia = new int[]{0,0};
        //update the count of recent failed logins
        ia[0]++;  
        //update the minute of the last failed login
        ia[1] = Math2.roundToInt(System.currentTimeMillis() / Calendar2.MILLIS_PER_MINUTE);  
        if (wasNull)
            failedLogins.put(user, ia);
    }

    /** This is used by doLogin when a users successfully logs in 
     *(to remove failed login attempts from failedLogins) */
    public void loginSucceeded(String user) {
        if (verbose) String2.log("loginSucceeded " + user);
        EDStatic.tally.add("Log in succeeded (since startup)", user);
        EDStatic.tally.add("Log in succeeded (since last daily report)", user);
        //erase any info about failed logins
        failedLogins.remove(user);

        //clear failedLogins ~ every ~48.3 hours (just larger than 48 hours (2880 min), 
        //  so it occurs at different times of day)
        //this prevents failedLogins from accumulating never-used-again userNames
        //at worst, someone who just failed 3 times now appears to have failed 0 times; no big deal
        //but do it after a success, not a failure, so even that is less likely
        if (lastClearedFailedLogins + 2897L * Calendar2.MILLIS_PER_MINUTE < System.currentTimeMillis()) {
            if (verbose) String2.log("clearing failedLogins (done every few days)");
            lastClearedFailedLogins = System.currentTimeMillis();
            failedLogins.clear();
        }

    }

    /** This returns the number of minutes until the user can try to log in 
     * again (0 = now, 10 is max temporarily locked out).
     */
    public int minutesUntilLoginAttempt(String user) {
        int ia[] = (int[])failedLogins.get(user);

        //no recent attempt?
        if (ia == null)
            return 0;

        //greater than 10 minutes since last attempt?
        int minutesSince = Math2.roundToInt(System.currentTimeMillis() / Calendar2.MILLIS_PER_MINUTE - ia[1]);
        int minutesToGo = Math.max(0, 10 - minutesSince);
        if (minutesToGo == 0) { 
            failedLogins.remove(user); //erase any info about failed logins
            return 0;
        }

        //allow login if <3 recent failures
        if (ia[0] < 3) {
            return 0;
        } else {
            EDStatic.tally.add("Log in attempt blocked temporarily (since startup)", user);
            EDStatic.tally.add("Log in attempt blocked temporarily (since last daily report)", user);
            if (verbose) String2.log("minutesUntilLoginAttempt=" + minutesToGo + " " + user);
            return minutesToGo;
        }
    }

    
    
    /**
     * This responds by prompting the user to login (e.g., login.html).
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     */
    public void doLogin(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs) throws Throwable {

        //Special case: "loggedInAsLoggingIn" is used by login.html
        //so that https is used for erddapUrl substitutions, 
        //but &amp;loginInfo; indicates user isn't logged in.
        String tLoggedInAs = loggedInAs == null? EDStatic.loggedInAsLoggingIn : loggedInAs;

        String tErddapUrl = EDStatic.erddapUrl(tLoggedInAs);
        String loginUrl = EDStatic.erddapHttpsUrl + "/login.html";
        String userQuery = request.getQueryString(); //may be null;  leave encoded
        String message = request.getParameter("message");
        String redMessage = message == null? "" :
            "<font class=\"warningColor\"><pre>" + 
            XML.encodeAsHTML(message) +  //encoding is important to avoid security problems (HTML injection)
            "</pre></font>\n";                   
        String problemsLoggingIn = 
            "<p>&nbsp;<hr><p><b>Problems?</b>\n" +
            "<p>If you have problems logging in:\n" +
            "<ul>\n" +
            "<li>Make sure that you log in with the exact same &info; that you gave to the ERDDAP administrator.\n" +
            "  <br>Uppercase and lower case letters are different.  Capital 'oh' and 'zero' are different.\n" +
            "  <br>&nbsp;\n" +
            "<li>Your password must be 7 or more characters long.\n" +
            "  <br>&nbsp;\n" +
            "<li>Make sure your browser is set to allow\n" +
            "  <a href=\"http://en.wikipedia.org/wiki/HTTP_cookie\">cookies</a>:\n" +
            "  <ul>\n" +
            "  <li>In Internet Explorer, use \"Tools : Internet Options : Privacy\"\n" +
            "  <li>In Firefox, use \"Tools : Options : Privacy\"\n" +
            "  <li>In Opera, use \"Tools : Quick Preferences\"\n" +
            "  <li>In Safari, use \"Safari : Preferences : Security\"\n" +
            "  </ul>\n" +
            "  If you are making ERDDAP requests from a computer program (other than a browser),\n" +
            "  cookies are hard to work with. Sorry.\n" +
            "  <br>&nbsp;\n" +
            "<li>If you fail to log in three times, you are locked out. Wait 10 minutes and try again.\n" +
            "  <br>&nbsp;\n" +
            "<li>Contact the administrator of this ERDDAP: " + EDStatic.adminContact() + ".\n" +
            "</ul>\n" +
            "\n";
        String problemsAfterLoggedIn = 
            "<p>&nbsp;<hr>\n" +
            "<p><b>If you are having problems ...</b>\n" +
            "<ul>\n" +
            "<li>You just logged in, but private datasets in ERDDAP still aren't visible\n" +
            "<li>You just logged in, but some ERDDAP web pages indicate you aren't logged in.\n" +
            "  <br>And if you try to log in again, ERDDAP says you are already logged in.\n" +
            "<li>You logged in a while ago and now, after a period of inactivity, ERDDAP says you aren't logged in.\n" +
            "</ul>\n" +
            "<p><b>Try these solutions ...</b>\n" +
            "<ul>\n" +
            "<li>ERDDAP URLs are slightly different for users who are logged in and users who aren't\n" +
            "  <br>(\"https:\" vs. \"http:\", and sometimes other differences).\n" +
            "  <br>Don't use your browser's <tt>Back</tt> button. Use the \"ERDDAP\" link above,\n" +
            "  <br>then use other links to go to ERDDAP pages you are interested in.\n" +
            "  <br>&nbsp;\n" +
            "<li>Click on your browser's Refresh button.\n" +
            "  <br>Your browser may have cached the web page before you logged in.\n" +
            "  <br>&nbsp;\n" +
            "<li>If, after a period of inactivity, ERDDAP says you aren't logged in, you have to log in again.\n" +
            "  <br>&nbsp;\n" +
            "<li>If you have multiple accounts, make sure you are logged in with the appropriate name to see the desired datasets.\n" +
            "  <br>&nbsp;\n" +
            "&second;" +
            "<li>Contact the administrator of this ERDDAP: " + EDStatic.adminContact() + ".\n" +
            "  <br>It is possible that some datasets are not currently available or that you don't have permission to access them.\n" +
            "</ul>\n" +
            "\n";
        String publicAccess = "(You don't have to be logged in to access ERDDAP's publicly available datasets.)\n";
        String attemptBlocked1 = ERROR + ": Login attempts for ";
        String attemptBlocked2 = " are being temporarily blocked after 3 failed attempts.\nWait ";
        String attemptBlocked3 = " minutes, then try again.";

        //if authentication is active ...
        if (!EDStatic.authentication.equals("")) {
            //if request was sent to http:, redirect to https:
            String actualUrl = request.getRequestURL().toString();
            if (EDStatic.baseHttpsUrl != null && EDStatic.baseHttpsUrl.startsWith("https://") && //EDStatic ensures this is true
                !actualUrl.startsWith("https://")) {
                //hopefully this won't happen much
                //hopefully headers and other info won't be lost in the redirect
                response.sendRedirect(loginUrl +
                    (userQuery == null || userQuery.length() == 0? "" : "?" + userQuery));
                return;            
            }
        }


        //*** BASIC
        /*
        if (EDStatic.authentication.equals("basic")) {

            //this is based on the example code in Java Servlet Programming, pg 238

            //since login is external, there is no way to limit login attempts to 3 tries in 10 minutes

            //write the html for the form 
            OutputStream out = getHtmlOutputStream(request, response);
            Writer writer = getHtmlWriter(tLoggedInAs, "Log In", out);
            try {
                writer.write(EDStatic.youAreHere(tLoggedInAs, "Log In"));

                //show message from EDStatic.redirectToLogin (which redirects to here) or logout.html
                writer.write(redMessage);           

                writer.write("<p>This ERDDAP is configured to let you log in by entering your User Name and Password.\n");

                if (loggedInAs == null) {
                    //I don't think this can happen; users must be logged in to see this page
                    writer.write(
                    "<p><b>Something is wrong!</b> Your browser should have asked you to log in to see this web page!\n" +
                    "<br>Tell the ERDDAP administrator to check the &lt;tomcat&gt;/conf/web.xml file.\n" +
                    "<p>" + publicAccess);

                } else {
                    //tell user he is logged in
                    writer.write("<p><font class=\"successColor\">You are logged in as <b>" + loggedInAs + "</b></font>\n" +
                        "(<a href=\"" + EDStatic.erddapHttpsUrl + "/logout.html\">log out</a>)\n");
                }
            
            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
            }
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }
        */


        //*** CUSTOM
        if (EDStatic.authentication.equals("custom")) {

            //is user trying to log in?
            String user =     request.getParameter("user");
            String password = request.getParameter("password");
            //justPrintable is good security and makes EDStatic.loggedInAsSuperuser special
            if (user     != null) user     = String2.justPrintable(user);   
            if (password != null) password = String2.justPrintable(password);
            if (loggedInAs == null &&   //can't log in if already logged in
                user != null && user.length() > 0 && password != null) {
                int minutesUntilLoginAttempt = minutesUntilLoginAttempt(user);
                if (minutesUntilLoginAttempt > 0) {
                    response.sendRedirect(loginUrl + "?message=" + 
                        SSR.minimalPercentEncode(attemptBlocked1 + user + attemptBlocked2 + 
                            minutesUntilLoginAttempt + attemptBlocked3));
                    return;
                }
                try {
                    if (EDStatic.doesPasswordMatch(user, password)) {
                        //valid login
                        HttpSession session = request.getSession(); //make one if one doesn't exist
                        //it is stored on server.  user doesn't have access, so can't spoof it
                        //  (except by guessing the sessionID number (a long) and storing a cookie with it?)
                        session.setAttribute("loggedInAs:" + EDStatic.warName, user);  
//??? should I create/add a ticket number to session so it can't be spoofed???
                        loginSucceeded(user);
                        response.sendRedirect(loginUrl);
                        return;
                    } else {
                        //invalid login;  if currently logged in, logout
                        HttpSession session = request.getSession(false); //don't make one if one doesn't exist
                        if (session != null) {
                            session.removeAttribute("loggedInAs:" + EDStatic.warName);
                            session.invalidate();
                        }
                        loginFailed(user);
                        response.sendRedirect(loginUrl + "?message=" + SSR.minimalPercentEncode(
                            ERROR + ": Login failed: Invalid User Name and/or Password."));
                        return;
                    }
                } catch (Throwable t) {
                    response.sendRedirect(loginUrl +
                        "?message=" + ERROR + ": Login failed: " + 
                        SSR.minimalPercentEncode(MustBe.getShortErrorMessage(t)));
                    return;
                }
            }

            OutputStream out = getHtmlOutputStream(request, response);
            Writer writer = getHtmlWriter(tLoggedInAs, "Log In", out);
            try {
                writer.write(EDStatic.youAreHere(tLoggedInAs, "Log In"));

                //show message from EDStatic.redirectToLogin (which redirects to here) or logout.html
                writer.write(redMessage);           

                writer.write("<p>This ERDDAP is configured to let you log in by entering your User Name and Password.\n");

                if (loggedInAs == null) {
                    //show the login form
                    writer.write(
                    "<p>You are not logged in.\n" +
                    publicAccess +
                    //use POST, not GET, so that form params (password!) aren't in url (and so browser history, etc.)
                    "<form action=\"login.html\" method=\"post\" id=\"login_form\">\n" +  
                    "<p><b>Please log in:</b>\n" +
                    "<table border=\"0\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                    "  <tr>\n" +
                    "    <td>User Name: </td>\n" +
                    "    <td><input type=\"text\" size=\"30\" value=\"\" name=\"user\" id=\"user\"/></td>\n" +
                    "  </tr>\n" +
                    "  <tr>\n" +
                    "    <td>Password: </td>\n" + 
                    "    <td><input type=\"password\" size=\"20\" value=\"\" name=\"password\" id=\"password\"/>\n" +
                    "      <input type=\"submit\" value=\"Login\"/></td>\n" +
                    "  </tr>\n" +
                    "</table>\n" +
                    "</form>\n" +
                    "\n" +
                    String2.replaceAll(problemsLoggingIn, "&info;", "User Name and Password"));               

                } else {
                    //tell user he is already logged in
                    writer.write("<p><font class=\"successColor\">You are logged in as <b>" + loggedInAs + "</b>.</font>\n" +
                        "(<a href=\"" + EDStatic.erddapHttpsUrl + "/logout.html\">log out</a>)\n" +
                        "<p><b>Don't use your browser's <tt>Back</tt> button. Use the \"ERDDAP\" link above,</b>\n" +
                        "<br>then use other links to go to ERDDAP pages you are interested in.\n" +
                        String2.replaceAll(problemsAfterLoggedIn, "&second;", ""));
                }
            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
            }
            
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }


        //*** OpenID
        if (EDStatic.authentication.equals("openid")) {

            //this is based on the example code at http://joid.googlecode.com/svn/trunk/examples/server/login.jsp

            //check if user is requesting to signin, before writing content
            String oid = request.getParameter("openid_url");
            if (loggedInAs != null) {
                loginSucceeded(loggedInAs); //this over-counts successes (any time logged-in user visits login page)
            }
            if (loggedInAs == null &&   //can't log in if already logged in
                request.getParameter("signin") != null && oid != null && oid.length() > 0) {

                //first thing: normalize oid
                if (!oid.startsWith("http")) 
                    oid = "http://" + oid;

                //check if loginAttempt is allowed  AFTER oid has been normalized
                int minutesUntilLoginAttempt = minutesUntilLoginAttempt(oid);
                if (minutesUntilLoginAttempt > 0) {
                    response.sendRedirect(loginUrl + "?message=" + 
                        SSR.minimalPercentEncode(attemptBlocked1 + oid + attemptBlocked2 + 
                            minutesUntilLoginAttempt + attemptBlocked3));
                    return;
                }
                try {
                    String returnTo = loginUrl;

                    //tally as if login failed -- AFTER oid has been normalized
                    //this assumes it will fail (a success will initially be counted as a failure)
                    loginFailed(oid); 

                    //Future: read about trust realms: http://openid.net/specs/openid-authentication-2_0.html#realms
                    //Maybe this could be used to authorize a group of erddaps, e.g., https://*.pfeg.noaa.gov:8443
                    //But I think it is just an informative string for the user.
                    String trustRealm = EDStatic.erddapHttpsUrl; //i.e., logging into all of this erddap  (was loginUrl;)
                    String s = OpenIdFilter.joid().getAuthUrl(oid, returnTo, trustRealm);
                    String2.log("redirect to " + s);
                    response.sendRedirect(s);
                    return;
                } catch (Throwable t) {
                    response.sendRedirect(loginUrl +
                        "?message=" + ERROR + ": Login failed: " + 
                        SSR.minimalPercentEncode(MustBe.getShortErrorMessage(t)));
                    return;
                }
            }
         
            //write the html for the openID info and form
            OutputStream out = getHtmlOutputStream(request, response);
            Writer writer = getHtmlWriter(tLoggedInAs, "OpenID Log In", out);
            try {
                writer.write(EDStatic.youAreHere(tLoggedInAs,
                    "<img align=\"bottom\" src=\"" + //align=middle looks bad on safari
                    EDStatic.imageDirUrl(tLoggedInAs) + "openid.png\" alt=\"OpenID\"/>OpenID Log In"));

                //show message from EDStatic.redirectToLogin (which redirects to here) or logout.html
                writer.write(redMessage);           

                //OpenID info
                writer.write(
                    "<p><a href=\"http://openid.net/\">OpenID</a> is an open standard that lets you\n" +
                    "log in with your password at one web site\n" +
                    "<br>and then log in without your password at many other web sites, including ERDDAP.\n");

                if (loggedInAs == null) {
                    //show the login form
                    writer.write(
                    "<p>You are not logged in.\n" + 
                    publicAccess +
                    "<script type=\"text/javascript\">\n" +
                    "  function submitForm(url){\n" +
                    "    document.getElementById(\"openid_url\").value = url;\n" +
                    "    document.getElementById(\"openid_form\").submit();\n" +
                    "  }\n" +
                    "</script>\n" +
                    //they use POST, not GET, probably so that form params (password!) aren't in url (and so browser history, etc.)
                    "<form action=\"login.html\" method=\"post\" id=\"openid_form\">\n" +  
                    "  <input type=\"hidden\" name=\"signin\" value=\"true\"/>\n" +
                    "  <p><b>Log in with your OpenID URL:</b>\n" +
                    "  <input type=\"text\" size=\"30\" value=\"\" name=\"openid_url\" id=\"openid_url\"/>\n" +
                    "  <input type=\"submit\" value=\"Login\"/>\n" +
                    "  <br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<small>For example: <tt>http://yourId.myopenid.com/</tt></small>\n" +
                    "</form>\n" +
                    "<br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Or log in to your existing (non-OpenID) account at \n" +
                    "<img align=\"middle\" src=\"http://l.yimg.com/us.yimg.com/i/ydn/openid-signin-yellow.png\" \n" +
                        "alt=\"Sign in with Yahoo\" onclick=\"submitForm('http://www.yahoo.com');\"/>\n" +
                    "<br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Or create an OpenID URL at\n" + 
                    "<a href=\"http://www.myopenid.com/\" target=\"_blank\">MyOpenID</a> (recommended), \n" +
                    "<a href=\"https://pip.verisignlabs.com/\" target=\"_blank\">Verisign</a>, or\n" +
                    "<a href=\"https://myvidoop.com/\" target=\"_blank\">Vidoop</a>. (They are all free!)\n" +
                    "\n" +
                    String2.replaceAll(problemsLoggingIn, "&info;", "OpenID URL"));

                } else {
                    //tell user he is already logged in
                    String s = String2.replaceAll(problemsAfterLoggedIn, "&second;", 
                        "<li>Make sure that you logged in with the exact same OpenID URL that you gave to the ERDDAP administrator.\n" +
                        "  <br>&nbsp;\n");
                    writer.write("<p><font class=\"successColor\">You are logged in as <b>" + loggedInAs + "</b>.</font>\n" +
                        "(<a href=\"" + EDStatic.erddapHttpsUrl + "/logout.html\">log out</a>)\n" + 
                        s);

                }
            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
            }
            
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }

        //*** Other
        //response.sendError(HttpServletResponse.SC_UNAUTHORIZED, 
        //    "This ERDDAP is not set up to let users log in.");
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(tLoggedInAs, "Log In", out);
        try {
            writer.write(
                EDStatic.youAreHere(tLoggedInAs, "Log In") +
                redMessage +
                "<p>This ERDDAP is not configured to let users log in.\n");       
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }
        endHtmlWriter(out, writer, tErddapUrl, false);
        return;
    }

    /**
     * This responds to a logout.html request.
     * This doesn't display a web page.
     * This does react to the request and redirect to another web page.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     */
    public void doLogout(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs) throws Throwable {

        String loginUrl = EDStatic.erddapHttpsUrl + "/login.html";

        try {       
            //user wasn't logged in?
            String youWerentLoggedIn = "You weren't logged in.";
            String encodedYouWerentLoggedIn = "?message=" + 
                SSR.minimalPercentEncode(youWerentLoggedIn);
            if (loggedInAs == null && !EDStatic.authentication.equals("basic")) {
                //user wasn't logged in
                response.sendRedirect(loginUrl + encodedYouWerentLoggedIn);
                return;
            }

            //user was logged in
            HttpSession session = request.getSession(false); //false = don't make a session if none currently
            String successMessage = 
                "You have successfully logged out.\n" +
                "\n" +
                "Don't use your browser's Back button. Use the \"ERDDAP\" link above,\n" +
                "then use other links to go to ERDDAP pages you are interested in.\n" +
                "\n" +
                "\n" +
                "For increased security (especially if this is a shared computer), you can:\n" +
                " * Clear your browser's cache:\n" +
                "   * In Internet Explorer, use \"Tools : Delete Browsing History : Delete Files\"\n" +
                "   * In Firefox, use \"Tools : Clear Private Data\"\n" +
                "   * In Opera, use \"Tools : Delete Private Data\"\n" +
                "   * In Safari, use \"Safari : Empty Cache\"\n";
            String encodedSuccessMessage = "?message=" + SSR.minimalPercentEncode(successMessage);

            //*** BASIC
            /*
            if (EDStatic.authentication.equals("basic")) {
                if (session != null) {
                    //!!!I don't think this works!!!
                    ArrayList al = String2.toArrayList(session.getAttributeNames());
                    for (int i = 0; i < al.size(); i++)
                        session.removeAttribute(al.get(i).toString());
                    session.invalidate();
                }
                EDStatic.tally.add("Log out (since startup)", "success");
                EDStatic.tally.add("Log out (since last daily report)", "success");

                //show the log out web page.   
                //Don't return to login.html, which triggers logging in again.
                String tErddapUrl = EDStatic.erddapUrl(tLoggedInAs);
                OutputStream out = getHtmlOutputStream(request, response);
                Writer writer = getHtmlWriter(tLoggedInAs, "Log Out", out);
                try {
                    writer.write(EDStatic.youAreHere(tLoggedInAs, "Log Out"));
                    if (loggedInAs == null) { 
                        //never was logged in 
                        writer.write(youWerentLoggedIn);
                    } else {
                        //still logged in?
                        loggedInAs = EDStatic.getLoggedInAs(request);
                        if (loggedInAs == null) {
                            //successfully logged out
                            String s = String2.replaceAll(successMessage, "\n", "\n<br>");
                            s = String2.replaceAll(successMessage, "   ", " &nbsp; ");
                            writer.write(s);       
                        } else {
                            //couldn't log user out!
                            writer.write(
                                "ERDDAP is having trouble logging you out.\n" +
                                "<br>To log out, please close your browser.\n");
                        }
                    }
                } catch (Throwable t) {
                    writer.write(EDStatic.htmlForException(t));
                }
                endHtmlWriter(out, writer, tErddapUrl, false);                
                return;
            }
            */

            //*** CUSTOM
            if (EDStatic.authentication.equals("custom")) {
                if (session != null) { //should always be !null
                    session.removeAttribute("loggedInAs:" + EDStatic.warName);
                    session.invalidate();
                    EDStatic.tally.add("Log out (since startup)", "success");
                    EDStatic.tally.add("Log out (since last daily report)", "success");
                }
                response.sendRedirect(loginUrl + encodedSuccessMessage);
                return;
            }

            //*** OpenID
            if (EDStatic.authentication.equals("openid")) {
                if (session != null) {  //should always be !null
                    OpenIdFilter.logout(session);
                    session.removeAttribute("user");
                    session.invalidate();
                    EDStatic.tally.add("Log out (since startup)", "success");
                    EDStatic.tally.add("Log out (since last daily report)", "success");
                }
                response.sendRedirect(loginUrl + encodedSuccessMessage +
                    SSR.minimalPercentEncode(" * Log out from your OpenID provider."));
                return;
            }

            //*** Other    (shouldn't get here)
            response.sendRedirect(loginUrl + encodedYouWerentLoggedIn);
            return;

        } catch (Throwable t) {
            response.sendRedirect(loginUrl + 
                "?message=" + SSR.minimalPercentEncode(MustBe.getShortErrorMessage(t)));
            return;
        }
    }

    /**
     * This responds to a request for status.html.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     */
    public void doStatus(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Status", out);
        try {
            int nGridDatasets = gridDatasetHashMap.size();
            int nTableDatasets = tableDatasetHashMap.size();
            writer.write(
                EDStatic.youAreHere(loggedInAs, "Status") +
                "<pre>");
            StringBuilder sb = new StringBuilder();
            EDStatic.addIntroStatistics(sb);

            //append number of active threads
            String traces = MustBe.allStackTraces(true, true);
            int po = traces.indexOf('\n');
            if (po > 0)
                sb.append(traces.substring(0, po + 1));

            sb.append(Math2.memoryString() + " " + Math2.xmxMemoryString() + "\n\n");
            EDStatic.addCommonStatistics(sb);
            sb.append(traces);
            writer.write(XML.encodeAsHTML(sb.toString()));
            writer.write("</pre>");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * This responds by sending out the "Computer Programs"/REST information Html page.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     */
    public void doRestHtml(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Computer Programs", out);
        try {
            String htmlQueryUrl = tErddapUrl + "/search/index.html?searchFor=temperature";
            String jsonQueryUrl = tErddapUrl + "/search/index.json?searchFor=temperature";
            String htmlQueryUrlWithSpaces = htmlQueryUrl + "%20wind%20speed";
            String griddapExample  = tErddapUrl + "/griddap/" + EDStatic.EDDGridIdExample;
            String tabledapExample = tErddapUrl + "/tabledap/" + EDStatic.EDDTableIdExample;
            writer.write(
                EDStatic.youAreHere(loggedInAs, "Computer Programs") +
                "<table style=\"width:640px; border-style:outset; border-width:0px; padding:0px; \" \n" +
                "  cellspacing=\"0\">\n" +
                "<tr>\n" +
                "<td>\n" +
                "\n" +
                "<h2 align=\"center\"><a name=\"WebService\">Accessing</a> ERDDAP as a Web Service</h2>\n" +
                "ERDDAP is both:\n" +
                "<ul>\n" +
                "<li><a href=\"http://en.wikipedia.org/wiki/Web_application\">A web application</a> \n" +
                "  &ndash; a web site that humans with browsers can use\n" +
                "  (in this case, to get data, graphs, and information about datasets).\n" +
                "  <br>&nbsp;\n" +
                "<li><a href=\"http://en.wikipedia.org/wiki/Web_service\">A web service</a> \n" +
                "  &ndash; a web site that computer programs can use\n" +
                "  (in this case, to get data, graphs, and information about datasets).\n" +
                "</ul>\n" +
                "For every ERDDAP feature that you as a human with a browser can use, there is an\n" +
                "<br>almost identical feature that is designed to be easy for computer programs to use.\n" +
                "For example, humans can use this URL to do a Full Text Search for datasets of interest:\n" +
                "<br><a href=\"" + htmlQueryUrl + "\">" + htmlQueryUrl + "</a>\n" +
                "<br>By changing the file extension in the URL from .html to .json:\n" +
                "<br><a href=\"" + jsonQueryUrl + "\">" + jsonQueryUrl + "</a>\n" +
                "<br>we get a URL that a computer program or JavaScript script can use to get the same\n" +
                "information in a more computer-program-friendly format like\n" +
                "<a href=\"http://www.json.org/\">JSON</a>.\n" +
                "\n" +
                "<p><b>Build Things on Top of ERDDAP</b>\n" +
                "<br>There are many features in ERDDAP that can be used by computer programs or scripts\n" +
                "that you write. You can use them to build other web applications or web services on\n" +
                "top of ERDDAP, making ERDDAP do most of the work!\n" +
                "So if you have an idea for a better interface to the data that ERDDAP serves or a web\n" +
                "page that needs an easy way to access data, we encourage you to build your own web\n" +
                "web application, web service, or web page and use ERDDAP as the foundation.\n" +
                "Your system can get data, graphs, and other information from ERD's ERDDAP or from\n" +
                "other ERDDAP installations, or you can \n" +
                //setup always from coastwatch's erddap 
                "  <a href=\"http://coastwatch.pfeg.noaa.gov/erddap/download/setup.html\">set up your own ERDDAP server</a>,\n" + 
                "  which can be\n" +
                "publicly accessible or just privately accessible.\n" +
                "\n" +
                //requests
                "<p><a name=\"requests\"><b>URL Requests</b></a>\n" +
                "<br>Requests for user-interface information from ERDDAP (for example, search results)\n" +
                "use the web's universal standard for requests:\n" +
                "<a href=\"http://en.wikipedia.org/wiki/Uniform_Resource_Locator\">URLs</a>\n" +
                "sent via\n" +
                "<a href=\"http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.3\">HTTP GET</a>.\n" +
                "This is the same mechanism that your browser uses when you fill out a form\n" +
                "on a web page and click on <tt>Submit</tt>.\n" +
                "To use HTTP GET, you generate a specially formed URL (which may include a query)\n" +
                "and send it with HTTP GET.  You can form these URLs by hand and enter them in\n" +
                "the address textfield of your browser (for example,\n" +
                "<br><a href=\"" + jsonQueryUrl + "\">" + jsonQueryUrl + "</a>)\n" +
                "<br>Or, you can write a computer program or web page script to create a URL, send it,\n" +
                "and get the response.  URLs via HTTP GET were chosen because\n" +
                "<ul>\n" +
                "<li> They are simple to use.\n" +
                "<li> They work.\n" +
                "<li> They are universally supported (in browsers, computer languages, operating system\n" +
                "  tools, etc).\n" +
                "<li> They are a foundation of\n" +
                "  <a href=\"http://en.wikipedia.org/wiki/Representational_State_Transfer\">Representational State Transfer (REST)</a> and\n" +
                "  <a href=\"http://www.crummy.com/writing/RESTful-Web-Services/\">Resource Oriented Architecture (ROA)</a>.\n" +
                "<li> They facilitate using the World Wide Web as a big distributed application,\n" +
                "  for example via\n" +
                "  <a href=\"http://en.wikipedia.org/wiki/Mashup_%28web_application_hybrid%29\">mashups</a> and\n" +
                "  <a href=\"http://en.wikipedia.org/wiki/Ajax_%28programming%29\">AJAX applications</a>.\n" +
                "<li> They are <a href=\"http://en.wikipedia.org/wiki/Stateless_protocol\">stateless</a>,\n" +
                "  as is ERDDAP, which makes the system simpler.\n" +
                "<li> A URL completely define a given request, so you can bookmark it in your browser,\n" +
                "  write it in your notes, email it to a friend, etc.\n" +
                "</ul>\n" +
                "\n" +
                "<p><a name=\"PercentEncode\"><b>Percent Encoding</b></a>\n" +
                "<br>In URLs, some characters are not allowed (for example, spaces) and other characters\n" +
                "have special meanings (for example, '&amp;' separates key=value pairs in a query).\n" +
                "When you fill out a form on a web page and click on Submit, your browser automatically\n" +
                "<a href=\"http://en.wikipedia.org/wiki/Percent-encoding\">percent encodes</a>\n" +
                "  the special characters in the URL (for example, by replacing ' ' in a query\n" +
                "value with \"%20\", for example,\n" +
                "<br><a href=\"" + htmlQueryUrlWithSpaces + "\">" + htmlQueryUrlWithSpaces + "</a>\n" +
                "<br>But if your computer program or script generates the URLs, it may need to do the percent\n" +
                "encoding itself.  Programming languages have tools to do this (for example, see Java's\n" +
                "<a href=\"http://download.oracle.com/javase/1.4.2/docs/api/java/net/URLEncoder.html\">java.net.URLEncoder</a>).\n" +
                "\n" +  
                
                //compression
                "<p>" + OutputStreamFromHttpResponse.acceptEncodingHtml +
                "\n" +
                //responses
                "<p><a name=\"responses\"><b>Response File Types</b></a>\n" +
                "<br>Although humans using browsers want to receive user-interface results (for example,\n" +
                "search results) as HTML documents, computer programs often prefer to get results in\n" +
                "simple, easily parsed, less verbose documents.  ERDDAP can return user-interface\n" +
                "results as a table of data in these common, computer-program friendly, file types:\n" +
                "<ul>\n" + //list of plainFileTypes
                "<li>.csv - a comma-separated ASCII text table.\n" +
                    "(<a href=\"http://en.wikipedia.org/wiki/Comma-separated_values\">more&nbsp;info</a>)\n" +
                "<li>.htmlTable - an .html web page with the data in a table.\n" +
                    "(<a href=\"http://www.w3schools.com/html/html_tables.asp\">more&nbsp;info</a>)\n" +
                "<li>.json - a table-like JSON file.\n" +
                    "(<a href=\"http://www.json.org/\">more&nbsp;info</a>)\n" +
                "<li>.mat - a MATLAB binary file.\n" +
                    "(<a href=\"http://www.mathworks.com/\">more&nbsp;info</a>)\n" +
                "<li>.nc - a flat, table-like, NetCDF-3 binary file.\n" +
                    "(<a href=\"http://www.unidata.ucar.edu/software/netcdf/\">more&nbsp;info</a>)\n" +
                "<li>.tsv - a tab-separated ASCII text table.\n" +
                    "(<a href=\"http://www.cs.tut.fi/~jkorpela/TSV.html\">more&nbsp;info</a>)\n" +
                "<li>.xhtml - an XHTML (XML) file with the data in a table.\n" +
                    "(<a href=\"http://www.w3schools.com/html/html_tables.asp\">more&nbsp;info</a>)\n" +
                "</ul>\n" +
                "In every results table:\n" +
                "<ul>\n" +
                "<li>Each column has a column name and one type of information.\n" +
                "<li>The first row of the table has the column names.\n" +
                "<li>Subsequent rows have the information you requested.\n" +
                "</ul>\n" +
                "<p>The content in these plain file types is also slightly different from the .html\n" +
                "response -- it is intentionally bare-boned, so that it is easier for a computer\n" +
                "program to work with.\n" +
                "\n" +
                "<p><a name=\"DataStructure\">A Consistent Data Structure for the Responses</a>\n" +
                "<br>All of the user-interface services described on this page can return a table of\n" +
                "data in any of the common file formats listed above. Hopefully, you can write\n" +
                "just one procedure to parse a table of data in one of the formats. Then you can\n" +
                "re-use that procedure to parse the response from any of these services.  This\n" +
                "should make it easier to deal with ERDDAP.\n" +
                "\n" +
                //csvIssues
                "<p><a name=\"csvIssues\">.csv</a> and .tsv Details<ul>\n" +
                "<li>If a datum in a .csv file has internal double quotes or commas, ERDDAP follows the\n" +
                "  .csv specification strictly: it puts double quotes around the datum and doubles\n" +
                "  the internal double quotes.\n" +
                "<li>If a datum in a .csv or .tsv file has internal newline characters, ERDDAP converts\n" +
                "  the newline characters to character #166 (&brvbar;). This is non-standard.\n" +
                "</ul>\n" +
                "\n" +
                //jsonp
                "<p><a name=\"jsonp\">jsonp</a>\n" +
                "<br>Requests for .json files may now include an optional" +
                "  <a href=\"http://niryariv.wordpress.com/2009/05/05/jsonp-quickly/\">jsonp</a> request by\n" +
                "adding \"&amp;.jsonp=<i>functionName</i>\" to the end of the query.  Basically, this tells\n" +
                "ERDDAP to add \"<i>functionName</i>(\" to the beginning of the response and \")\" to the\n" +
                "end of the response. If originally there was no query, leave off the \"&amp;\" in your query.\n" +
                "\n" + 

                "<p>griddap and tabledap Offer Different File Types\n" +
                "<br>The file types listed above are file types ERDDAP can use to respond to\n" +
                "user-interface types of requests (for example, search requests). ERDDAP supports\n" +
                "a different set of file types for scientific data (for example, satellite and buoy\n" +
                "data) requests (see the \n" +
                "  <a href=\"" + tErddapUrl + "/griddap/documentation.html#fileType\">griddap</a> and\n" +
                "  <a href=\"" + tErddapUrl + "/tabledap/documentation.html#fileType\">tabledap</a>\n" +
                "  documentation.\n" +
                "\n" +
                //accessUrls
                "<p><a name=\"accessUrls\"><b>Access URLs for ERDDAP's Services</b></a>\n" +
                "<br>ERDDAP has these URL access points for computer programs:\n" +
                "<ul>\n" +
                "<li>To get the list of the <b>main resource access URLs</b>, use\n" +
                "  <br>" + plainLinkExamples(tErddapUrl, "/index", "") +
                "  <br>&nbsp;\n" +
                "<li>To get the current list of all <b>datasets</b>, use\n" + 
                "  <br>" + plainLinkExamples(tErddapUrl, "/info/index", "") + 
                "  <br>&nbsp;\n" +
                "<li>To get <b>metadata</b> for a specific data set\n" +
                "  (the list of variables and their attributes), use\n" + 
                "  <br>" + tErddapUrl + "/info/<i>datasetID</i>/index<i>.fileType</i>\n" +
                "  <br>for example,\n" + 
                "  <br>" + plainLinkExamples(tErddapUrl, "/info/" + EDStatic.EDDGridIdExample + "/index", "") + 
                "  <br>&nbsp;\n" +
                "<li>To get the results of <b>full text searches</b> for datasets\n" +
                "  (using \"searchFor=temperature%20wind%20speed\" as the example), use\n" +
                "  <br>" + plainLinkExamples(tErddapUrl, "/search/index", "?searchFor=temperature%20wind%20speed") +
                "  <br>(Your program or script may need to \n" +
                "    <a href=\"http://en.wikipedia.org/wiki/Percent-encoding\">percent-encode</a>\n" +
                "    the value in the query.)\n" +
                "  <br>&nbsp;\n" +
                "<li>To get the results of <b>advanced searches</b> for datasets\n" +
                "  (using \"searchFor=temperature%20wind%20speed\" as the example), use\n" +
                "  <br>" + plainLinkExamples(tErddapUrl, "/search/advanced", "?searchFor=temperature%20wind%20speed") +
                "  <br>But experiment with\n" +
                "    <a href=\"" + tErddapUrl + "/search/advanced.html\">Advanced Search</a>\n" +
                "    in a browser to figure out all of the\n" +
                "  optional parameters.\n" +
                "  (Your program or script may need to \n" +
                "    <a href=\"http://en.wikipedia.org/wiki/Percent-encoding\">percent-encode</a>\n" +
                "    the value in the query.)\n" +
                "  <br>&nbsp;\n" +
                "<li>To get the list of <b>categoryAttributes</b>\n" +
                "  (e.g., institution, long_name, standard_name), use\n" +
                "  <br>" + plainLinkExamples(tErddapUrl, "/categorize/index", "") +
                "  <br>&nbsp;\n" +
                "<li>To get the list of <b>categories for a specific categoryAttribute</b>\n" +
                "  (using \"standard_name\" as the example), use\n" +
                "  <br>" + plainLinkExamples(tErddapUrl, "/categorize/standard_name/index", "") +
                "  <br>&nbsp;\n" +
                "<li>To get the list of <b>datasets in a specific category</b>\n" +
                "  (using \"standard_name=time\" as the example), use\n" +
                "  <br>" +  plainLinkExamples(tErddapUrl, "/categorize/standard_name/time/index", ""));
            int tDasIndex = String2.indexOf(EDDTable.dataFileTypeNames, ".das");
            int tDdsIndex = String2.indexOf(EDDTable.dataFileTypeNames, ".dds");
            writer.write(
                "  <br>&nbsp;\n" +
                "<li>To get the current list of datasets available via a specific <b>protocol</b>,\n" +
                "  <ul>\n" +
                "  <li>For griddap: use\n" +
                    plainLinkExamples(tErddapUrl, "/griddap/index", "") +
                "  <li>For tabledap: use\n" +
                    plainLinkExamples(tErddapUrl, "/tabledap/index", ""));
            if (EDStatic.sosActive) writer.write(
                "  <li>For SOS: use\n" +
                    plainLinkExamples(tErddapUrl, "/sos/index", ""));
            if (EDStatic.wcsActive) writer.write(
                "  <li>For WCS: use\n" +
                    plainLinkExamples(tErddapUrl, "/wcs/index", ""));
            writer.write(
                "  <li>For WMS: use\n" +
                    plainLinkExamples(tErddapUrl, "/wms/index", "") +
                "  <br>&nbsp;\n" +
                "  </ul>\n" +
                "<li>Griddap and tabledap have many web services that you can use.\n" +
                "  <ul>\n" +
                "  <li>The Data Access Forms are just simple web pages to generate URLs which\n" +
                "    request <b>data</b> (e.g., satellite and buoy data).  The data can be in any of\n" +
                "    several common file formats. Your program can generate these URLs directly.\n" +
                "    For more information, see the\n" +
                "      <a href=\"" + tErddapUrl + "/griddap/documentation.html\">griddap documentation</a> and\n" +
                "    <a href=\"" + tErddapUrl + "/tabledap/documentation.html\">tabledap documentation</a>.\n" +
                "    <br>&nbsp;\n" +
                "  <li>The Make A Graph pages are just simple web pages to generate URLs which\n" +
                "    request <b>graphs</b> of a subset of the data.  The graphs can be in any of several\n" +
                "    common file formats.  Your program can generate these URLs directly. For\n" +
                "    more information, see the\n" +
                "      <a href=\"" + tErddapUrl + "/griddap/documentation.html\">griddap documentation</a> and\n" +
                "      <a href=\"" + tErddapUrl + "/tabledap/documentation.html\">tabledap documentation</a>.\n" +
                "    <br>&nbsp;\n" +
                "  <li>To get a <b>dataset's structure</b>, including variable names and data types,\n" +
                "    use a standard OPeNDAP\n" +
                "      <a href=\"" + XML.encodeAsHTML(EDDTable.dataFileTypeInfo[tDdsIndex]) + "\">.dds</a>\n" +
                "      resquest. For example,\n" + 
                "    <br><a href=\"" + griddapExample  + ".dds\">" + griddapExample  + ".dds</a> or\n" +
                "    <br><a href=\"" + tabledapExample + ".dds\">" + tabledapExample + ".dds</a> .\n" +
                "    <br>&nbsp;\n" +
                "  <li>To get a <b>dataset's metadata</b>, use a standard OPeNDAP\n" +
                "      <a href=\"" + XML.encodeAsHTML(EDDTable.dataFileTypeInfo[tDdsIndex]) + "\">.das</a>\n" +
                "      resquest.\n" +
                "    For example,\n" + 
                "    <br><a href=\"" + griddapExample  + ".das\">" + griddapExample  + ".das</a> or\n" +
                "    <br><a href=\"" + tabledapExample + ".das\">" + tabledapExample + ".das</a> .\n" +
                "    <br>&nbsp;\n" +
                "  </ul>\n" +
                "<li>ERDDAP's other protocols also have web services that you can use.\n" +
                "  <br>See ERDDAP's\n");
            if (EDStatic.sosActive) writer.write(
                "    <a href=\"" + tErddapUrl + "/sos/documentation.html\">SOS</a>,\n");
            if (EDStatic.wcsActive) writer.write(
                 "    <a href=\"" + tErddapUrl + "/wcs/documentation.html\">WCS</a>,\n");
            if (EDStatic.sosActive || EDStatic.wcsActive) writer.write(
                "    and\n");
            writer.write(
                "    <a href=\"" + tErddapUrl + "/wms/documentation.html\">WMS</a> documentation.\n" +
                "    <br>&nbsp;\n" +
                "<li>ERDDAP offers \n" +
                "    <a href=\"" + tErddapUrl + "/information.html#subscriptions\">RSS subscriptions</a>,\n" +
                "    so that your computer program find out if a\n" +
                "  dataset has changed.\n" +
                "  <br>&nbsp;\n" +
                "<li>ERDDAP offers \n" +
                "    <a href=\"" + tErddapUrl + "/information.html#subscriptions\">email/URL subscriptions</a>,\n" +
                "    which notify your computer program\n" +
                "  whenever a dataset changes.\n" +
                "  <br>&nbsp;\n" +
                "<li>ERDDAP offers converter services:\n" +
                "  <ul>\n" +
                "  <li><a href=\"" + tErddapUrl + "/convert/fipscounty.html#computerProgram\">Convert a FIPS County Code to/from a County Name</a>\n" +
                "  <li><a href=\"" + tErddapUrl + "/convert/time.html#computerProgram\">Convert a Numeric Time to/from a String Time</a>\n" +
                "  <li><a href=\"" + tErddapUrl + "/convert/units.html#computerProgram\">Convert UDUNITS to/from Unified Code for Units of Measure (UCUM)</a>\n" +
                "  </ul>\n" +
                "  <br>&nbsp;\n" +
                "</ul>\n" +
                "If you have suggestions for additional links, contact <tt>bob dot simons at noaa dot gov</tt>.\n");

            //JavaPrograms
            writer.write(
                "<h2><a name=\"JavaPrograms\">Using</a> ERDDAP as a Data Source within Your Java Program</h2>\n" +
                "As described above, since Java programs can access data available on the web, you can\n" +
                "write a Java program that accesses data from any publicly accessible ERDDAP installation.\n" +
                "\n" +
                "<p>Or, since ERDDAP is an all-open source program, you can also set up your own copy of\n" +
                "ERDDAP on your own server (publicly accessible or not) to serve your own data. Your Java\n" +
                "programs can get data from that copy of ERDDAP. See\n" +
                //setup.html always from coastwatch's erddap
                "  <a href=\"http://coastwatch.pfeg.noaa.gov/erddap/download/setup.html\">Set Up Your Own ERDDAP</a>.\n");

            //erddap version
            writer.write(
                "<h2><a name=\"version\">ERDDAP Version</a></h2>\n" +
                "If you want to use a new feature on a remote ERDDAP, you can find out if the new\n" +
                "feature is available by sending a request to determine the ERDDAP's version\n" +
                "number, for example,\n" +
                "<br><a href=\"" + tErddapUrl + "/version\">" + tErddapUrl + "/version</a>" +
                "<br>ERDDAP will send a text response with the ERDDAP version number of that ERDDAP.\n" +
                "For example:\n" +
                "<tt>ERDDAP_version=" + EDStatic.erddapVersion + "</tt>\n" +
                "<br>If you get an <tt>HTTP 404 Not-Found</tt> error message, treat the ERDDAP as version\n" +
                "1.22 or lower.\n" +
                "\n" +                
                "</td>\n" +
                "</tr>\n" +
                "</table>\n");

        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }
        endHtmlWriter(out, writer, tErddapUrl, false);

    }

    /**
     * This responds by sending out the sitemap.xml file.
     * <br>See http://www.sitemaps.org/protocol.php
     * <br>This uses the startupDate as the lastmod date.
     * <br>This uses changefreq=monthly. Datasets may change in small ways (e.g., near-real-time data), 
     *   but that doesn't affect the metadata that search engines are interested in.
     */
    public void doSitemap(HttpServletRequest request, HttpServletResponse response) throws Throwable {

        //always use plain EDStatic.erddapUrl
        String pre = 
            "<url>\n" +
            "<loc>" + EDStatic.erddapUrl + "/";
        String basicPost =
            "</loc>\n" +
            "<lastmod>" + EDStatic.startupLocalDateTime.substring(0,10) + "</lastmod>\n" +
            "<changefreq>monthly</changefreq>\n" +
            "<priority>";
        //highPriority
        String postHigh = basicPost + "0.7</priority>\n" +  
            "</url>\n" +
            "\n";
        //medPriority
        String postMed = basicPost + "0.5</priority>\n" +  //0.5 is the default
            "</url>\n" +
            "\n";
        //lowPriority
        String postLow = basicPost + "0.3</priority>\n" +
            "</url>\n" +
            "\n";

        //beginning
        OutputStreamSource outSource = new OutputStreamFromHttpResponse(
            request, response, "sitemap", ".xml", ".xml");
        OutputStream out = outSource.outputStream("UTF-8");
        Writer writer = new OutputStreamWriter(out, "UTF-8");
        writer.write(
            "<?xml version='1.0' encoding='UTF-8'?>\n" +
            //this is their simple example
            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
            //this is what they recommend to validate it, but it doesn't validate for me ('urlset' not defined)
            //"<urlset xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            //"    xsi:schemaLocation=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n" +
            //"    url=\"http://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd\"\n" +
            //"    xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
            "\n");

        //write the individual urls
        //don't include the admin pages that all link to ERD's erddap
        //don't include setDatasetFlag.txt, setup.html, setupDatasetsXml.html, status.html, 
        writer.write(pre); writer.write("categorize/index.html");             writer.write(postMed);
        writer.write(pre); writer.write("convert/index.html");                writer.write(postMed);
        writer.write(pre); writer.write("convert/fipscounty.html");           writer.write(postHigh);
        writer.write(pre); writer.write("convert/time.html");                 writer.write(postHigh);
        writer.write(pre); writer.write("convert/units.html");                writer.write(postHigh);
        writer.write(pre); writer.write("griddap/documentation.html");        writer.write(postHigh);
        writer.write(pre); writer.write("griddap/index.html");                writer.write(postHigh);
        writer.write(pre); writer.write("images/embed.html");                 writer.write(postHigh);
        writer.write(pre); writer.write("images/gadgets/GoogleGadgets.html"); writer.write(postHigh);
        writer.write(pre); writer.write("index.html");                        writer.write(postHigh);
        writer.write(pre); writer.write("info/index.html");                   writer.write(postHigh); 
        writer.write(pre); writer.write("information.html");                  writer.write(postHigh);
        writer.write(pre); writer.write("legal.html");                        writer.write(postHigh);
        writer.write(pre); writer.write("rest.html");                         writer.write(postHigh);
        writer.write(pre); writer.write("search/index.html");                 writer.write(postHigh); 
        writer.write(pre); writer.write("slidesorter.html");                  writer.write(postHigh);
        if (EDStatic.sosActive) {
        writer.write(pre); writer.write("sos/documentation.html");            writer.write(postHigh);
        writer.write(pre); writer.write("sos/index.html");                    writer.write(postHigh);
        }
        writer.write(pre); writer.write("subscriptions/index.html");          writer.write(postHigh); 
        writer.write(pre); writer.write("subscriptions/add.html");            writer.write(postMed); 
        writer.write(pre); writer.write("subscriptions/validate.html");       writer.write(postMed);
        writer.write(pre); writer.write("subscriptions/list.html");           writer.write(postMed); 
        writer.write(pre); writer.write("subscriptions/remove.html");         writer.write(postMed);
        writer.write(pre); writer.write("tabledap/documentation.html");       writer.write(postHigh);
        writer.write(pre); writer.write("tabledap/index.html");               writer.write(postHigh);
        if (EDStatic.wcsActive) {
        writer.write(pre); writer.write("wcs/documentation.html");            writer.write(postHigh);
        writer.write(pre); writer.write("wcs/index.html");                    writer.write(postHigh);
        }
        writer.write(pre); writer.write("wms/documentation.html");            writer.write(postHigh);
        writer.write(pre); writer.write("wms/index.html");                    writer.write(postHigh);

        //special links only for ERD's erddap
        if (EDStatic.baseUrl.equals("http://coastwatch.pfeg.noaa.gov")) {
            writer.write(pre); writer.write("download/grids.html");                 writer.write(postHigh);
            writer.write(pre); writer.write("download/setup.html");                 writer.write(postHigh);
            writer.write(pre); writer.write("download/setupDatasetsXml.html");      writer.write(postHigh);
        }

        //write the dataset .html, .subset, .graph, wms, wcs, sos, ... urls
        StringArray sa = gridDatasetIDs(true);
        int n = sa.size();
        String gPre = pre + "griddap/";
        String iPre = pre + "info/";
        String tPre = pre + "tabledap/";
        String sPre = pre + "sos/";
        String cPre = pre + "wcs/";
        String mPre = pre + "wms/";
        for (int i = 0; i < n; i++) {
            //don't inlude index/datasetID, .das, .dds; better that people go to .html or .graph
            String dsi = sa.get(i);
            writer.write(gPre); writer.write(dsi); writer.write(".html");        writer.write(postMed);    
            writer.write(iPre); writer.write(dsi); writer.write("/index.html");  writer.write(postLow);
            //EDDGrid doesn't do SOS
            EDD edd = (EDD)gridDatasetHashMap.get(dsi);
            if (edd != null) {
                if (edd.accessibleViaMAG().length() == 0) {
                    writer.write(gPre); writer.write(dsi); writer.write(".graph");       writer.write(postMed);
                }
                if (EDStatic.wcsActive && edd.accessibleViaWCS().length() == 0) {
                    writer.write(cPre); writer.write(dsi); writer.write("/index.html");  writer.write(postLow);
                }
                if (edd.accessibleViaWMS().length() == 0) {
                    writer.write(mPre); writer.write(dsi); writer.write("/index.html");  writer.write(postLow);
                }
            }
        }

        sa = tableDatasetIDs(true);
        n = sa.size();
        for (int i = 0; i < n; i++) {
            String dsi = sa.get(i);
            writer.write(tPre); writer.write(dsi); writer.write(".html");        writer.write(postMed);
            writer.write(iPre); writer.write(dsi); writer.write("/index.html");  writer.write(postLow);
            //EDDTable currently don't do wms or wcs
            EDD edd = (EDD)tableDatasetHashMap.get(dsi);
            if (edd != null) {
                if (edd.accessibleViaMAG().length() == 0) {
                    writer.write(tPre); writer.write(dsi); writer.write(".graph");       writer.write(postMed);
                }
                if (edd.accessibleViaSubset().length() == 0) {
                    writer.write(tPre); writer.write(dsi); writer.write(".subset");      writer.write(postMed);
                }
                if (EDStatic.sosActive && edd.accessibleViaSOS().length() == 0) {
                    writer.write(sPre); writer.write(dsi); writer.write("/index.html");  writer.write(postLow);
                }
            }
        }

        //write the category urls
        for (int ca1 = 0; ca1 < EDStatic.categoryAttributes.length; ca1++) {
            String ca1InURL = EDStatic.categoryAttributesInURLs[ca1];
            StringArray cats = categoryInfo(ca1InURL);
            int nCats = cats.size();
            String catPre = pre + "categorize/" + ca1InURL + "/";
            writer.write(catPre); writer.write("index.html"); writer.write(postMed);
            for (int ca2 = 0; ca2 < nCats; ca2++) {
                writer.write(catPre); writer.write(cats.get(ca2)); writer.write("/index.html"); writer.write(postMed);
            }
        }

        //end
        writer.write(
            "</urlset>\n"); 
        writer.close(); //it flushes, it closes 'out'
    }

    /**
     * This is used to generate examples for the plainFileTypes in the method above.
     * 
     * @param tErddapUrl  from EDStatic.erddapUrl(loggedInAs)  (erddapUrl, or erddapHttpsUrl if user is logged in)
     * @param relativeUrl e.g., "/griddap/index"
     * @return a string with a series of html links to information about the plainFileTypes
     */
    protected String plainLinkExamples(String tErddapUrl,
        String relativeUrl, String query) throws Throwable {

        StringBuilder sb = new StringBuilder();
        int n = plainFileTypes.length;
        for (int pft = 0; pft < n; pft++) {
            sb.append(
                "    <a href=\"" + tErddapUrl + relativeUrl + plainFileTypes[pft] + query + "\">" + 
                plainFileTypes[pft] + "</a>");
            if (pft <= n - 3) sb.append(",\n");
            if (pft == n - 2) sb.append(", or\n");
            if (pft == n - 1) sb.append(".\n");
        }
        return sb.toString();
    }

    /**
     * Process a grid or table OPeNDAP DAP-style request.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    ("griddap" or "tabledap") in the requestUrl
     * @param userDapQuery  post "?".  Still percentEncoded.
     */
    public void doDap(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs,
        String protocol, int datasetIDStartsAt, String userDapQuery) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);       
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String fileTypeName = "";
        try {

            String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
                requestUrl.substring(datasetIDStartsAt);

            //respond to a documentation.html request
            if (endOfRequestUrl.equals("documentation.html")) {

                OutputStream out = getHtmlOutputStream(request, response);
                Writer writer = getHtmlWriter(loggedInAs, protocol + " Documentation", out); 
                try {
                    writer.write(EDStatic.youAreHere(loggedInAs, protocol, "Documentation"));
                    if (protocol.equals("griddap"))       EDDGrid.writeGeneralDapHtmlInstructions(tErddapUrl, writer, true);
                    else if (protocol.equals("tabledap")) EDDTable.writeGeneralDapHtmlInstructions(tErddapUrl, writer, true);
                } catch (Throwable t) {
                    writer.write(EDStatic.htmlForException(t));
                }
                endHtmlWriter(out, writer, tErddapUrl, false);
                return;
            }

            //first, always set the standard DAP response header info
            standardDapHeader(response);

            //redirect to index.html
            if (endOfRequestUrl.equals("") ||
                endOfRequestUrl.equals("index.htm")) {
                response.sendRedirect(tErddapUrl + "/" + protocol + "/index.html");
                return;
            }      
            
            //respond to a version request (see opendap spec section 7.2.5)
            if (endOfRequestUrl.equals("version") ||
                endOfRequestUrl.startsWith("version.") ||
                endOfRequestUrl.endsWith(".ver")) {

                //write version response 
                //DAP 2.0 7.1.1 says version requests DON'T include content-description header.
                OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                    request, response, "version", //fileName is not used
                    ".txt", ".txt");
                OutputStream out = outSource.outputStream("ISO-8859-1");
                Writer writer = new OutputStreamWriter(out, "ISO-8859-1");
                writer.write( 
                    "Core Version: " + dapVersion + OpendapHelper.EOL + //see EOL definition for comments
                    "Server Version: " + serverVersion + OpendapHelper.EOL +
                    "ERDDAP_version: " + EDStatic.erddapVersion + OpendapHelper.EOL); 

                //DODSServlet always does this if successful     done automatically?
                //response.setStatus(HttpServletResponse.SC_OK);

                //essential
                writer.flush();
                if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
                out.close(); 

                return;
            }

            //respond to a help request  (see opendap spec section 7.2.6)
            //Note that lack of fileType (which opendap spec says should lead to help) 
            //  is handled elsewhere with error message and help 
            //  (which seems appropriate and mimics other dap servers)
            if (endOfRequestUrl.equals("help") ||
                endOfRequestUrl.startsWith("help.") ||                
                endOfRequestUrl.endsWith(".help")) {

                //write help response 
                //DAP 2.0 7.1.1 says help requests DON'T include content-description header.
                OutputStreamSource outputStreamSource = 
                    new OutputStreamFromHttpResponse(request, response, 
                        "help", ".html", ".html");
                OutputStream out = outputStreamSource.outputStream("ISO-8859-1");
                Writer writer = new OutputStreamWriter(
                    //DAP 2.0 section 3.2.3 says US-ASCII (7bit), so might as well go for compatible common 8bit
                    out, "ISO-8859-1");
                writer.write(EDStatic.startHeadHtml(tErddapUrl, protocol + " Help"));
                writer.write("\n</head>\n");
                writer.write(EDStatic.startBodyHtml(loggedInAs));
                writer.write("\n");
                writer.write(HtmlWidgets.htmlTooltipScript(EDStatic.imageDirUrl(loggedInAs)));     
                writer.write(EDStatic.youAreHere(loggedInAs, protocol, "Help"));
                writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better
                try {
                    if (protocol.equals("griddap")) 
                        EDDGrid.writeGeneralDapHtmlInstructions(tErddapUrl, writer, true); //true=complete
                    if (protocol.equals("tabledap")) 
                        EDDTable.writeGeneralDapHtmlInstructions(tErddapUrl, writer, true); //true=complete

                    if (EDStatic.displayDiagnosticInfo) 
                        EDStatic.writeDiagnosticInfoHtml(writer);
                    writer.write(EDStatic.endBodyHtml(tErddapUrl));
                    writer.write("\n</html>\n");
                } catch (Throwable t) {
                    writer.write(EDStatic.htmlForException(t));
                }
                //essential
                writer.flush();
                if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
                out.close(); 

                return;
            }

            //get the datasetID and requested fileType
            int dotPo = endOfRequestUrl.lastIndexOf('.');
            if (dotPo < 0) 
                throw new SimpleException("URL error: " +
                    "No file type (e.g., .html) was specified after the datasetID.");

            String id = endOfRequestUrl.substring(0, dotPo);
            fileTypeName = endOfRequestUrl.substring(dotPo);
            if (reallyVerbose) String2.log("  id=" + id + "\n  fileTypeName=" + fileTypeName);

            //respond to xxx/index request
            //show list of 'protocol'-supported datasets in .html file
            if (id.equals("index")) {
                sendDatasetList(request, response, loggedInAs, protocol, fileTypeName);
                return;
            }

            //get the dataset
            EDD dataset = protocol.equals("griddap")? 
                (EDD)gridDatasetHashMap.get(id) : 
                (EDD)tableDatasetHashMap.get(id);
            if (dataset == null) {
                sendResourceNotFoundError(request, response, 
                    "Currently unknown datasetID=" + id);
                return;
            }
            if (!dataset.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { //listPrivateDatasets doesn't apply
                EDStatic.redirectToLogin(loggedInAs, response, id);
                return;
            }
            if (fileTypeName.equals(".graph") && dataset.accessibleViaMAG().length() > 0) {
                sendResourceNotFoundError(request, response, dataset.accessibleViaMAG());
                    return;
            }
            if (fileTypeName.equals(".subset") && dataset.accessibleViaSubset().length() > 0) {
                sendResourceNotFoundError(request, response, dataset.accessibleViaSubset());
                return;
            }

            EDStatic.tally.add(protocol + " DatasetID (since startup)", id);
            EDStatic.tally.add(protocol + " DatasetID (since last daily report)", id);
            EDStatic.tally.add(protocol + " File Type (since startup)", fileTypeName);
            EDStatic.tally.add(protocol + " File Type (since last daily report)", fileTypeName);
            String fileName = dataset.suggestFileName(loggedInAs, userDapQuery, fileTypeName);
            String extension = dataset.fileTypeExtension(fileTypeName);
            if (reallyVerbose) String2.log("  fileName=" + fileName + "\n  extension=" + extension);
            if (fileTypeName.equals(".subset")) {
                String tValue = (userDapQuery == null || userDapQuery.length() == 0)? 
                    "initial request" : "subsequent request";
                EDStatic.tally.add(".subset (since startup)", tValue);
                EDStatic.tally.add(".subset (since last daily report)", tValue);
                EDStatic.tally.add(".subset DatasetID (since startup)", id);
                EDStatic.tally.add(".subset DatasetID (since last daily report)", id);
            }

            String cacheDir = dataset.cacheDirectory(); //it is created by EDD.ensureValid
            OutputStreamSource outputStreamSource = 
                new OutputStreamFromHttpResponse(request, response, 
                    fileName, fileTypeName, extension);

            //if EDDGridFromErddap or EDDTableFromErddap, forward request
            //Note that .html and .graph are handled locally so links on web pages 
            //  are for this server and the reponses can be handled quickly.
            String tqs = request.getQueryString();  //still encoded
            if (tqs == null) tqs = "";
            if (tqs.length() > 0) tqs = "?" + tqs;
            if (dataset instanceof FromErddap) {
                FromErddap fromErddap = (FromErddap)dataset;
                double sourceVersion = fromErddap.sourceErddapVersion();
                //some requests are handled locally...
                if (!fileTypeName.equals(".html") && 
                    !fileTypeName.equals(".graph") &&
                    !fileTypeName.endsWith("ngInfo") &&  //pngInfo EDD.readPngInfo makes local file in all cases
                    !fileTypeName.endsWith("dfInfo") &&  //pdfInfo
                    //for old remote erddaps, make .png locally so pngInfo is available
                    !(fileTypeName.equals(".png") && sourceVersion <= 1.22) &&
                    !fileTypeName.equals(".subset")) { 
                    //redirect the request
                    String tUrl = fromErddap.getPublicSourceErddapUrl() + fileTypeName;
                    if (verbose) String2.log("redirected to " + tUrl + tqs);
                    response.sendRedirect(tUrl + tqs);  
                    return;
                }
            }

            //*** tell the dataset to send the data
            try {
                dataset.respondToDapQuery(request, response,
                    loggedInAs, requestUrl, userDapQuery, 
                    outputStreamSource, 
                    cacheDir, fileName, fileTypeName);
            } catch (WaitThenTryAgainException wttae) {
                String2.log("!!ERDDAP caught WaitThenTryAgainException");
                //is response committed?
                if (response.isCommitted()) {
                    String2.log("but the response is already committed. So rethrowing the error.");
                    throw wttae;
                }

                //wait up to 30 seconds for dataset to reload 
                //This also slows down the client (esp. if a script) and buys time for erddap.
                int waitSeconds = 30;
                for (int sec = 0; sec < waitSeconds; sec++) {
                    //sleep for a second
                    Math2.sleep(1000); 

                    //has the dataset finished reloading?
                    EDD dataset2 = protocol.equals("griddap")? 
                        (EDD)gridDatasetHashMap.get(id) : 
                        (EDD)tableDatasetHashMap.get(id);
                    if (dataset2 != null && dataset != dataset2) { //yes, simplistic !=,  not !equals
                        //yes! ask dataset2 to respond to the query

                        //does user still have access to the dataset?
                        if (!dataset2.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { //listPrivateDatasets doesn't apply
                            EDStatic.redirectToLogin(loggedInAs, response, id);
                            return;
                        }

                        try {
                            //note that this will fail if the previous reponse is already committed
                            dataset2.respondToDapQuery(request, response, loggedInAs,
                                requestUrl, userDapQuery, outputStreamSource, 
                                dataset2.cacheDirectory(), fileName, //dir is created by EDD.ensureValid
                                fileTypeName);
                            String2.log("!!ERDDAP successfully used dataset2 to respond to the request.");
                            break; //success! jump out of for(sec) loop
                        } catch (Throwable t) {
                            String2.log("!!!!ERDDAP caught Exception while trying WaitThenTryAgainException:\n" +
                                MustBe.throwableToString(t));
                            throw wttae; //throw original error
                        }
                    }

                    //if the dataset didn't reload after waitSeconds, throw the original error
                    if (sec == waitSeconds - 1)
                        throw wttae;
                }
            }

            //DODSServlet always does this if successful     //???is it done by default???
            //response.setStatus(HttpServletResponse.SC_OK);

            OutputStream out = outputStreamSource.outputStream("");
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); //essential, to end compression
            return;

        } catch (Throwable t) {

            //deal with the DAP error

            //catch errors after the response has begun
            if (neededToSendErrorCode(request, response, t))
                return;

            //display dap error message in a web page
            boolean isDapType = 
                (fileTypeName.equals(".asc")  ||
                 fileTypeName.equals(".das")  || 
                 fileTypeName.equals(".dds")  || 
                 fileTypeName.equals(".dods") ||
                 fileTypeName.equals(".html"));

            if (isDapType) {
                OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                    request, response, "error", //fileName is not used
                    fileTypeName, fileTypeName);
                OutputStream out = outSource.outputStream("ISO-8859-1");
                Writer writer = new OutputStreamWriter(out, "ISO-8859-1");

                //see DAP 2.0, 7.2.4  for error structure               
                //see dods.dap.DODSException for codes.  I don't know (here), so use 3=Malformed Expr
                String error = MustBe.getShortErrorMessage(t);
                if (error != null && error.length() >= 2 &&
                    error.startsWith("\"") && error.endsWith("\""))
                    error = error.substring(1, error.length() - 1);
                writer.write("Error {\n" +
                    "  code = 3 ;\n" +
                    "  message = \"" +
                        String2.replaceAll(error, "\"", "\\\"") + //see DAP appendix A, quoted-string    
                        "\" ;\n" +
                    "} ; "); //thredds has final ";"; spec doesn't

                //essential
                writer.flush();
                if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
                out.close(); 
            } else { 
                //other file types, e.g., .csv, .json, .mat,
                throw t;
            }
        }
    }


    /**
     * This sends the list of graph or table datasets
     *
     * @param request
     * @param response
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param protocol   must be "griddap", "tabledap", "sos", "wcs", or "wms"
     * @param fileTypeName e.g., .html or .json
     * throws Throwable if trouble
     */
    public void sendDatasetList(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, String protocol, String fileTypeName) throws Throwable {

        //is it a valid fileTypeName?
        int pft = String2.indexOf(plainFileTypes, fileTypeName);
        if (pft < 0 && !fileTypeName.equals(".html")) {
            sendResourceNotFoundError(request, response, "Unsupported fileType=" + fileTypeName);
            return;
        }

        //gather the datasetIDs and descriptions
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        StringArray ids;
        String description;  
        if      (protocol.equals("griddap")) {
            ids = gridDatasetIDs(true);
            description = EDStatic.EDDGridDapDescription;
        } else if (protocol.equals("tabledap")) {
            ids = tableDatasetIDs(true);
            description = EDStatic.EDDTableDapDescription;
        } else if (EDStatic.sosActive && protocol.equals("sos")) {
            ids = new StringArray();
            StringArray tids = tableDatasetIDs(true);
            for (int ti = 0; ti < tids.size(); ti++) {
                EDDTable eddTable = (EDDTable)tableDatasetHashMap.get(tids.get(ti));
                if (eddTable != null && //if just deleted
                    eddTable.accessibleViaSOS().length() == 0) 
                    ids.add(eddTable.datasetID());
            }
            description = EDStatic.sosDescriptionHtml +
               "<br>For details, see the 'S'OS links below.";
        } else if (EDStatic.wcsActive && protocol.equals("wcs")) {
            ids = new StringArray();
            StringArray gids = gridDatasetIDs(true);
            for (int gi = 0; gi < gids.size(); gi++) {
                EDDGrid eddGrid = (EDDGrid)gridDatasetHashMap.get(gids.get(gi));
                if (eddGrid != null && //if just deleted
                    eddGrid.accessibleViaWCS().length() == 0) 
                    ids.add(eddGrid.datasetID());
            }
            description = EDStatic.wcsDescriptionHtml;
        } else if (protocol.equals("wms")) {
            ids = new StringArray();
            StringArray gids = gridDatasetIDs(true);
            for (int gi = 0; gi < gids.size(); gi++) {
                EDDGrid eddGrid = (EDDGrid)gridDatasetHashMap.get(gids.get(gi));
                if (eddGrid != null && //if just deleted
                    eddGrid.accessibleViaWMS().length() == 0) 
                    ids.add(eddGrid.datasetID());
            }
            description = EDStatic.wmsDescriptionHtml;
        } else {
            sendResourceNotFoundError(request, response, "Unknown protocol=" + protocol);
            return;
        }

        String uProtocol = protocol.equals("sos") || protocol.equals("wcs") || protocol.equals("wms")? 
            protocol.toUpperCase() : protocol;
        description = String2.replaceAll(description, '\n', ' '); //remove inherent breaks
        description =
            "&nbsp;\n<br>" +
            String2.noLongLinesAtSpace(description, 90, "<br>");

        //you can't use noLongLinesAtSpace for fear of  "<a <br>href..."
        if (!protocol.equals("sos"))
            description +=
            "\n<br>For details, see ERDDAP's <a href=\"" + tErddapUrl + "/" + protocol +  
            "/documentation.html\">" + uProtocol + " Documentation</a>.\n";
        
        //handle plainFileTypes   
        if (pft >= 0) {
            //make the plain table with the dataset list
            Table table = makePlainDatasetTable(loggedInAs, ids, true, fileTypeName);  
            sendPlainTable(loggedInAs, request, response, table, protocol, fileTypeName);
            return;
        }


        //make the html table with the dataset list
        Table table = makeHtmlDatasetTable(loggedInAs, ids, true);  

        //display start of web page
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "List of " + uProtocol + " Datasets", out); 
        try {
            writer.write(
                //EDStatic.youAreHere(loggedInAs, uProtocol));

                getYouAreHereTable(
                    EDStatic.youAreHere(loggedInAs, uProtocol) +
                    description,
                    //Or, View All Datasets
                    "&nbsp;\n" +
                    "<br>" + getSearchFormHtml(loggedInAs, "Or, ", ":\n<br>", "") +
                    "<p>" + getCategoryLinksHtml(tErddapUrl) +
                    "<p>Or, Refine this Search with " + 
                        getAdvancedSearchLink(loggedInAs, "protocol=" + uProtocol) + 
                    "&nbsp;&nbsp;&nbsp;"));


            if (table.nRows() == 0) {
                writer.write("\n<h2>" + EDStatic.noDatasetWith + " protocol = \"" + protocol + "\"</h2>");
            } else {
                writer.write("\n<h2>Datasets Which Can Be Accessed via " + uProtocol + "</h2>\n"
                    //+ "(Or, refine this search with " + 
                    //    getAdvancedSearchLink(loggedInAs, "protocol=" + uProtocol) + ")\n" +
                    //+ EDStatic.clickAccessHtml + "\n" +
                    //"<br>&nbsp;\n"
                    );
                table.saveAsHtmlTable(writer, "commonBGColor", null, 1, false, -1, false, false);        
                writer.write("<p>" + table.nRows() + " " + EDStatic.nDatasetsListed + "\n");
            }
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }


    /**
     * Process a SOS request -- NOT YET FINISHED.
     * This SOS service is intended to simulate the IOOS DIF SOS service (datasetID=ndbcSOS).
     * For IOOS DIF schemas, see http://www.csc.noaa.gov/ioos/schema/IOOS-DIF/ .
     * O&M document(?) says that query names are case insensitive, but query values are case sensitive.
     * Background info: http://www.opengeospatial.org/projects/groups/sensorweb     
     *
     * <p>This assumes request was for /erddap/sos.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    ("sos") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     */
    public void doSos(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, int datasetIDStartsAt, String userQuery) throws Throwable {

/*
This isn't finished!   Reference server (ndbcSOS) is in flux and ...
Interesting IOOS DIF info c:/programs/sos/EncodingIOOSv0.6.0Observations.doc
Spec questions? Ask Jeff DLb (author of WMS spec!): Jeff.deLaBeaujardiere@noaa.gov 
*/

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);

        //catch other reponses outside of try/catch  (so errors handled in doGet)
        if (endOfRequestUrl.equals("") || endOfRequestUrl.equals("index.htm")) {
            response.sendRedirect(tErddapUrl + "/sos/index.html");
            return;
        }

        //list the SOS datasets
        if (endOfRequestUrl.startsWith("index.")) {
            sendDatasetList(request, response, loggedInAs, "sos", endOfRequestUrl.substring(5)); 
            return;
        }        

        //SOS documentation web page
        if (endOfRequestUrl.equals("documentation.html")) {
            doSosDocumentation(request, response, loggedInAs);
            return;
        }       

        //request should be e.g., /sos/cwwcNdbc/[EDDTable.sosServer]?service=SOS&request=GetCapabilities
        String urlEndParts[] = String2.split(endOfRequestUrl, '/');
        String tDatasetID = urlEndParts.length > 0? urlEndParts[0] : "";
        String part1 = urlEndParts.length > 1? urlEndParts[1] : "";
        EDDTable eddTable = (EDDTable)tableDatasetHashMap.get(tDatasetID);
        if (eddTable == null) {
            sendResourceNotFoundError(request, response, 
                "Currently unknown datasetID=" + tDatasetID);
            return;
        }

        //check loggedInAs
        String roles[] = EDStatic.getRoles(loggedInAs);
        if (!eddTable.isAccessibleTo(roles)) {
            EDStatic.redirectToLogin(loggedInAs, response, tDatasetID);
            return;
        }

        //check accessibleViaSOS
        if (eddTable.accessibleViaSOS().length() > 0) {
            sendResourceNotFoundError(request, response, eddTable.accessibleViaSOS());
            return;
        }

        //write /sos/[datasetID]/index.html
        if (part1.equals("index.html") && urlEndParts.length == 2) {
//tally other things?
            EDStatic.tally.add("SOS index.html (since last daily report)", tDatasetID);
            EDStatic.tally.add("SOS index.html (since startup)", tDatasetID);
            OutputStream out = getHtmlOutputStream(request, response);
            Writer writer = getHtmlWriter(loggedInAs, XML.encodeAsHTML(eddTable.title()) + " - SOS", out);
            try {
                eddTable.sosDatasetHtml(loggedInAs, writer);
            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
            }
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }

        //write /sos/[datasetID]/phenomenaDictionary.xml
        if (part1.equals(EDDTable.sosPhenomenaDictionaryUrl) && urlEndParts.length == 2) {
            OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                request, response, "sos_" + eddTable.datasetID() + "_phenomenaDictionary", ".xml", ".xml");
            OutputStream out = outSource.outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");
            eddTable.sosPhenomenaDictionary(writer);
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 
            return;
        }


        //ensure it is a SOS server request
        if (!part1.equals(EDDTable.sosServer) && urlEndParts.length == 2) {
            sendResourceNotFoundError(request, response, "");
            return;
        }

        //No! Don't redirect! datasetID may be different so station and observedProperty names
        //  will be different.
        //if eddTable instanceof EDDTableFromErddap, redirect the request
        /*if (eddTable instanceof EDDTableFromErddap && userQuery != null) {
            //http://coastwatch.pfeg.noaa.gov/erddap/tabledap/erdGlobecBottle
            String tUrl = ((EDDTableFromErddap)eddTable).getNextLocalSourceErddapUrl();
            tUrl = String2.replaceAll(tUrl, "/tabledap/", "/sos/") + "/" + EDDTable.sosServer + 
                "?" + userQuery;
            if (verbose) String2.log("redirected to " + tUrl);
            response.sendRedirect(tUrl);  
            return;
        }*/

        //note that isAccessibleTo(loggedInAs) and accessibleViaSOS are checked above
        try {

            //parse SOS service userQuery  
            HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, true); //true=names toLowerCase

            //if service= is present, it must be service=SOS     //technically, it is required
            String tService = queryMap.get("service"); 
            if (tService != null && !tService.equals("SOS")) 
                //this format "Query error: xxx=" is parsed by Erddap section "deal with SOS error"
                throw new SimpleException("Query error: service='" + tService + "' must be 'SOS'."); 

            //deal with the various request= options
            String tRequest = queryMap.get("request");
            if (tRequest == null)
                tRequest = "";

            if (tRequest.equals("GetCapabilities")) {
                //e.g., ?service=SOS&request=GetCapabilities
                OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                    request, response, "sos_" + eddTable.datasetID() + "_capabilities", ".xml", ".xml");
                OutputStream out = outSource.outputStream("UTF-8");
                Writer writer = new OutputStreamWriter(out, "UTF-8");
                eddTable.sosGetCapabilities(writer, loggedInAs); 
                writer.flush();
                if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
                out.close(); 
                return;

            } else if (tRequest.equals("DescribeSensor")) {
                //The url might be something like
                //http://sdf.ndbc.noaa.gov/sos/server.php?request=DescribeSensor&service=SOS
                //  &version=1.0.0&outputformat=text/xml;subtype=%22sensorML/1.0.0%22
                //  &procedure=urn:ioos:sensor:noaa.nws.ndbc:41012:adcp0

                //version is not required. If present, it must be valid.
                String version = queryMap.get("version");  //map keys are lowercase
                if (version == null || !version.equals(EDDTable.sosVersion))
                    //this format "Query error: xxx=" is parsed by Erddap section "deal with SOS error"
                    throw new SimpleException("Query error: version='" + version + 
                        "' must be '" + EDDTable.sosVersion + "'."); 

                //outputFormat is not required. If present, it must be valid.
                //not different name and values than GetObservation responseFormat
                String outputFormat = queryMap.get("outputformat");  //map keys are lowercase
                if (outputFormat == null || !outputFormat.equals(EDDTable.sosDSOutputFormat))
                    //this format "Query error: xxx=" is parsed by Erddap section "deal with SOS error"
                    throw new SimpleException("Query error: outputFormat='" + outputFormat + 
                        "' must be '" + SSR.minimalPercentEncode(EDDTable.sosDSOutputFormat) + "'."); 

                //procedure=fullSensorID is required   (in getCapabilities, procedures are sensors)
                String procedure = queryMap.get("procedure");  //map keys are lowercase
                if (procedure == null)
                //this format "Query error: xxx=" is parsed by Erddap section "deal with SOS error"
                    throw new SimpleException("Query error: procedure=''.  Please specify a procedure."); 
                String sensorGmlNameStart = eddTable.getSosGmlNameStart("sensor");
                String shortName = procedure.startsWith(sensorGmlNameStart)?
                    procedure.substring(sensorGmlNameStart.length()) : procedure;
                //int cpo = platform.indexOf(":");  //now platform  or platform:sensor
                //String sensor = "";
                //if (cpo >= 0) {
                //    sensor = platform.substring(cpo + 1);
                //    platform = platform.substring(0, cpo);
                //}         
                if (!shortName.equals(eddTable.datasetID()) &&    //all
                    eddTable.sosOfferings.indexOf(shortName) < 0) //1 station
                    //this format "Query error: xxx=" is parsed by Erddap section "deal with SOS error"
                    throw new SimpleException("Query error: procedure=" + procedure +
                        " isn't a valid long or short sensor name."); 
                //if ((!sensor.equals(eddTable.datasetID()) &&    //all
                //     String2.indexOf(eddTable.dataVariableDestinationNames(), sensor) < 0) || //1 variable
                //        sensor.equals(EDV.LON_NAME) ||
                //        sensor.equals(EDV.LAT_NAME) ||
                //        sensor.equals(EDV.ALT_NAME) ||
                //        sensor.equals(EDV.TIME_NAME) ||
                //        sensor.equals(eddTable.dataVariableDestinationNames()[eddTable.idIndex()])) 
                //    this format "Query error: xxx=" is parsed by Erddap section "deal with SOS error"
                //    throw new SimpleException("Query error: procedure=" + procedure + " isn't valid because \"" +
                //        sensor + "\" isn't valid sensor name."); 

                //all is well. do it.
                String fileName = "sosSensor_" + eddTable.datasetID() + "_" + shortName;
                OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                    request, response, fileName, ".xml", ".xml");
                OutputStream out = outSource.outputStream("UTF-8");
                Writer writer = new OutputStreamWriter(out, "UTF-8");
                eddTable.sosDescribeSensor(loggedInAs, shortName, writer);
                writer.flush();
                if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
                out.close(); 
                return;

            } else if (tRequest.equals("GetObservation")) {
                String responseFormat = queryMap.get("responseformat");  //map keys are lowercase
                String fileTypeName = EDDTable.sosResponseFormatToFileTypeName(responseFormat);
                if (fileTypeName == null)
                    //this format "Query error: xxx=" is parsed by Erddap section "deal with SOS error"
                    throw new SimpleException("Query error: responseFormat=" + responseFormat + " is invalid."); 

                String responseMode = queryMap.get("responsemode");  //map keys are lowercase
                if (responseMode == null)
                    responseMode = "inline";
                String extension = null;
                if (EDDTable.isIoosSosXmlResponseFormat(responseFormat) || //throws exception if invalid format
                    EDDTable.isOostethysSosXmlResponseFormat(responseFormat) ||
                    responseMode.equals("out-of-band")) { //xml response with link to tabledap

                    extension = ".xml";
                } else {
                    int po = String2.indexOf(EDDTable.dataFileTypeNames, fileTypeName);
                    if (po >= 0) 
                        extension = EDDTable.dataFileTypeExtensions[po];
                    else {
                        po = String2.indexOf(EDDTable.imageFileTypeNames, fileTypeName);
                        extension = EDDTable.imageFileTypeExtensions[po];
                    }
                }
            
                String dir = eddTable.cacheDirectory();
                String fileName = "sos_" + eddTable.suggestFileName(loggedInAs, userQuery, responseFormat);
                OutputStreamSource oss = 
                    new OutputStreamFromHttpResponse(request, response, 
                        fileName, fileTypeName, extension);
                eddTable.sosGetObservation(userQuery, loggedInAs, oss, dir, fileName); //it calls out.close()
                return;

            } else {
                //this format "Query error: xxx=" is parsed by Erddap section "deal with SOS error"
                throw new SimpleException("Query error: request=" + tRequest + " is not supported."); 
            }

        } catch (Throwable t) {

            //deal with the SOS error
            //catch errors after the response has begun
            if (neededToSendErrorCode(request, response, t))
                return;

            OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                request, response, "ExceptionReport", //fileName is not used
                ".xml", ".xml");
            OutputStream out = outSource.outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");

            //for now, mimic oostethys  (ndbcSOS often doesn't throw exceptions)
            //exceptionCode options are from OGC 06-121r3  section 8
            //  the locator is the name of the relevant request parameter
//* OperationNotSupported  Request is for an operation that is not supported by this server
//* MissingParameterValue  Operation request does not include a parameter value, and this server did not declare a default value for that parameter
//* InvalidParameterValue  Operation request contains an invalid parameter value a
//* VersionNegotiationFailed  List of versions in “AcceptVersions” parameter value in GetCapabilities operation request did not include any version supported by this server
//* InvalidUpdateSequence  Value of (optional) updateSequence parameter in GetCapabilities operation request is greater than current value of service metadata updateSequence number
//* OptionNotSupported  Request is for an option that is not supported by this server
//* NoApplicableCode   No other exceptionCode specified by this service and server applies to this exception
            String error = MustBe.getShortErrorMessage(t);
            String exCode = "NoApplicableCode";  //default
            String locator = null;               //default

            //catch InvalidParameterValue 
            //Look for "Query error: xxx="
            String qe = "Query error: ";
            int qepo = error.indexOf(qe);
            int epo = error.indexOf('=');
            if (qepo >= 0 && epo > qepo && epo - qepo < 17 + 20) {
                exCode  = "InvalidParameterValue";
                locator = error.substring(qepo + qe.length(), epo);
            } 

            writer.write(
                "<?xml version=\"1.0\"?>\n" +
                "<ExceptionReport xmlns=\"http://www.opengis.net/ows\" \n" +
                "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" \n" +
                "  xsi:schemaLocation=\"http://www.opengis.net/ows http://schemas.opengis.net/ows/1.0.0/owsExceptionReport.xsd\" \n" +
                "  version=\"1.0.0\" language=\"en\">\n" +
                "  <Exception exceptionCode=\"" + exCode + "\" " +
                    (locator == null? "" : "locator=\"" + locator + "\" ") +
                    ">\n" +
                "    <ExceptionText>" + XML.encodeAsHTML(error) + "</ExceptionText>\n" +
                "  </Exception>\n" +
                "</ExceptionReport>\n");

            //essential
            writer.flush();
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 

        }
    }


    /**
     * This responds by sending out ERDDAP's "SOS Documentation" Html page.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     */
    public void doSosDocumentation(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "SOS Documentation", out);
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, "Sensor Observation Service (SOS)") +
                "\n" +
                "<h2>Overview</h2>\n" +
                "In addition to making data available via \n" +
                  "<a href=\"" + tErddapUrl + "/griddap/index.html\">gridddap</a> and \n" +
                  "<a href=\"" + tErddapUrl + "/tabledap/index.html\">tabledap</a>, \n" + 
                  "ERDDAP makes some datasets\n" +
                "<br>available via ERDDAP's Sensor Observation Service (SOS) web service.\n" +
                "\n" +
                "<p>" + EDStatic.sosLongDescriptionHtml + 
                "<p>See the\n" +
                "<a href=\"" + tErddapUrl + "/sos/index.html\">list of datasets available via SOS</a>\n" +
                "at this ERDDAP installation.\n" +
                "<br>The SOS web pages listed there for each dataset have further documentation and sample requests.\n" +
                "\n");

        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }
        endHtmlWriter(out, writer, tErddapUrl, false);

    }


    /**
     * Process a WCS request.
     * This WCS service is intended to simulate the THREDDS WCS service (version 1.0.0).
     * See http://www.unidata.ucar.edu/projects/THREDDS/tech/reference/WCS.html.
     * O&M document(?) says that query names are case insensitive, but query values are case sensitive.
     * Background info: http://www.opengeospatial.org/projects/groups/sensorweb     
     *
     * <p>This assumes request was for /erddap/wcs.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    ("wcs") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     */
    public void doWcs(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, int datasetIDStartsAt, String userQuery) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);

        //catch other reponses outside of try/catch  (so errors handled in doGet)
        if (endOfRequestUrl.equals("") || endOfRequestUrl.equals("index.htm")) {
            response.sendRedirect(tErddapUrl + "/wcs/index.html");
            return;
        }

        //list the WCS datasets
        if (endOfRequestUrl.startsWith("index.")) {
            sendDatasetList(request, response, loggedInAs, "wcs", endOfRequestUrl.substring(5)); 
            return;
        }        

        //WCS documentation web page
        if (endOfRequestUrl.equals("documentation.html")) {
            doWcsDocumentation(request, response, loggedInAs);
            return;
        }       

        //endOfRequestUrl should be erdMHchla8day/[EDDGrid.wcsServer]
        String urlEndParts[] = String2.split(endOfRequestUrl, '/');
        String tDatasetID = urlEndParts.length > 0? urlEndParts[0] : "";
        String part1 = urlEndParts.length > 1? urlEndParts[1] : "";
        EDDGrid eddGrid = (EDDGrid)gridDatasetHashMap.get(tDatasetID);
        if (eddGrid == null) {
            sendResourceNotFoundError(request, response, 
                "Currently unknown datasetID=" + tDatasetID);
            return;
        }

        //check loggedInAs
        String roles[] = EDStatic.getRoles(loggedInAs);
        if (!eddGrid.isAccessibleTo(roles)) {
            EDStatic.redirectToLogin(loggedInAs, response, tDatasetID);
            return;
        }

        //check accessibleViaWCS
        if (eddGrid.accessibleViaWCS().length() >  0) {
            sendResourceNotFoundError(request, response, eddGrid.accessibleViaWCS());
            return;
        }

        //write /wcs/[datasetID]/index.html
        if (part1.equals("index.html") && urlEndParts.length == 2) {
//tally other things?
            EDStatic.tally.add("WCS index.html (since last daily report)", tDatasetID);
            EDStatic.tally.add("WCS index.html (since startup)", tDatasetID);
            OutputStream out = getHtmlOutputStream(request, response);
            Writer writer = getHtmlWriter(loggedInAs, XML.encodeAsHTML(eddGrid.title()) + " - WCS", out);
            try {
                eddGrid.wcsDatasetHtml(loggedInAs, writer);
            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
            }
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }

        //ensure it is a SOS server request
        if (!part1.equals(EDDGrid.wcsServer) && urlEndParts.length == 2) {
            sendResourceNotFoundError(request, response, "");
            return;
        }

        //if eddGrid instanceof EDDGridFromErddap, redirect the request
        if (eddGrid instanceof EDDGridFromErddap && userQuery != null) {
            //http://coastwatch.pfeg.noaa.gov/erddap/griddap/erdMHchla8day
            String tUrl = ((EDDGridFromErddap)eddGrid).getPublicSourceErddapUrl();
            tUrl = String2.replaceAll(tUrl, "/griddap/", "/wcs/") + "/" + EDDGrid.wcsServer + 
                "?" + userQuery;
            if (verbose) String2.log("redirected to " + tUrl);
            response.sendRedirect(tUrl);  
            return;
        }

        try {

            //parse userQuery  
            HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, true); //true=names toLowerCase

            //if service= is present, it must be service=WCS     //technically, it is required
            String tService = queryMap.get("service"); 
            if (tService != null && !tService.equals("WCS"))
                throw new SimpleException("Query error: service='" + tService + "' must be 'WCS'."); 

            //deal with the various request= options
            String tRequest = queryMap.get("request"); //test .toLowerCase() 
            if (tRequest == null)
                tRequest = "";

            String tVersion = queryMap.get("version");   //test .toLowerCase() 
            String tCoverage = queryMap.get("coverage"); //test .toLowerCase() 

            if (tRequest.equals("GetCapabilities")) {  
                //e.g., ?service=WCS&request=GetCapabilities
                OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                    request, response, "wcs_" + eddGrid.datasetID() + "_capabilities", 
                    ".xml", ".xml");
                OutputStream out = outSource.outputStream("UTF-8");
                Writer writer = new OutputStreamWriter(out, "UTF-8");
                eddGrid.wcsGetCapabilities(loggedInAs, tVersion, writer); 
                writer.flush();
                if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
                out.close(); 
                return;
                
            } else if (tRequest.equals("DescribeCoverage")) { 
                //e.g., ?service=WCS&request=DescribeCoverage
                OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                    request, response, "wcs_" + eddGrid.datasetID()+ "_" + tCoverage, 
                    ".xml", ".xml");
                OutputStream out = outSource.outputStream("UTF-8");
                Writer writer = new OutputStreamWriter(out, "UTF-8");
                eddGrid.wcsDescribeCoverage(loggedInAs, tVersion, tCoverage, writer);
                writer.flush();
                if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
                out.close(); 
                return;

            } else if (tRequest.equals("GetCoverage")) {
                //e.g., ?service=WCS&request=GetCoverage
                //format
                String requestFormat = queryMap.get("format"); //test name.toLowerCase()
                String tRequestFormats[]  = EDDGrid.wcsRequestFormats100;  //version100? wcsRequestFormats100  : wcsRequestFormats112;
                String tResponseFormats[] = EDDGrid.wcsResponseFormats100; //version100? wcsResponseFormats100 : wcsResponseFormats112;
                int fi = String2.caseInsensitiveIndexOf(tRequestFormats, requestFormat);
                if (fi < 0)
                    throw new SimpleException("Query error: format=" + requestFormat + " isn't supported."); 
                String erddapFormat = tResponseFormats[fi];
                int efe = String2.indexOf(EDDGrid.dataFileTypeNames, erddapFormat);
                String fileExtension;
                if (efe >= 0) {
                    fileExtension = EDDGrid.dataFileTypeExtensions[efe];
                } else {
                    efe = String2.indexOf(EDDGrid.imageFileTypeNames, erddapFormat);
                    if (efe >= 0) {
                        fileExtension = EDDGrid.imageFileTypeExtensions[efe];
                    } else {
                        throw new SimpleException("Query error: format=" + requestFormat + " isn't supported!"); //slightly different
                    }
                }                   

                OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                    request, response, 
                    "wcs_" + eddGrid.datasetID() + "_" + tCoverage + "_" +
                        String2.md5Hex12(userQuery), //datasetID is already in file name
                    erddapFormat, fileExtension);
                eddGrid.wcsGetCoverage(loggedInAs, userQuery, outSource);
                return;

            } else {
                throw new SimpleException("Query error: request='" + tRequest + "' is not supported."); 
            }

        } catch (Throwable t) {

            //deal with the WCS error
            //catch errors after the response has begun
            if (neededToSendErrorCode(request, response, t))
                return;

            OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                request, response, "error", //fileName is not used
                ".xml", ".xml");
            OutputStream out = outSource.outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");

            //???needs work, see Annex A of 1.0.0 specification
            //this is based on mapserver's exception  (thredds doesn't have xmlns...)
            String error = MustBe.getShortErrorMessage(t);
            writer.write(
                "<ServiceExceptionReport\n" +
                "  xmlns=\"http://www.opengis.net/ogc\"\n" +
                "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +     //wms??? really???
                "  xsi:schemaLocation=\"http://www.opengis.net/ogc http://schemas.opengeospatial.net/wms/1.1.1/OGC-exception.xsd\">\n" +
                //there are others codes, see Table A.1; I don't differentiate.
                "  <ServiceException code='InvalidParameterValue'>\n" + 
                error + "\n" +
                "  </ServiceException>\n" +
                "</ServiceExceptionReport>\n");

            //essential
            writer.flush();
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 
        }
    }


    /**
     * This responds by sending out "ERDDAP's WCS Documentation" Html page.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     */
    public void doWcsDocumentation(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "WCS Documentation", out);
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, "Web Coverage Service (WCS)") +
                "\n" +
                "<h2>Overview</h2>\n" +
                "In addition to making data available via \n" +
                "<a href=\"" + tErddapUrl + "/griddap/index.html\">gridddap</a> and \n" +
                "<a href=\"" + tErddapUrl + "/tabledap/index.html\">tabledap</a>,\n" + 
                "ERDDAP makes some datasets available via ERDDAP's Web Coverage Service (WCS) web service.\n" +
                "\n" +
                "<p>See the\n" +
                "<a href=\"" + tErddapUrl + "/wcs/index.html\">list of datasets available via WCS</a>\n" +
                "at this ERDDAP installation.\n" +
                "\n" +
                "<p>" + EDStatic.wcsLongDescriptionHtml + "\n" +
                "\n" +
                "<p>WCS clients send HTTP POST or GET requests (specially formed URLs) to the WCS service and get XML responses.\n" +
                "Some WCS client programs are:\n" +
                "<ul>\n" +
                "<li><a href=\"http://pypi.python.org/pypi/OWSLib/\">OWSLib</a> (free) - a Python command line library\n" +
                "<li><a href=\"http://zeus.pin.unifi.it/cgi-bin/twiki/view/GIgo/WebHome\">GI-go</a> (free)\n" +
                "<li><a href=\"http://www.cadcorp.com/\">CADCorp</a> (commercial) - has a \"no cost\" product called\n" +
                "    <a href=\"http://www.cadcorp.com/products_geographical_information_systems/map_browser.htm\">Map Browser</a>\n" +
                "<li><a href=\"http://www.ittvis.com/ProductServices/IDL.aspx\">IDL</a> (commercial)\n" +
                "<li><a href=\"http://www.gvsig.gva.es/index.php?id=gvsig&amp;L=2\">gvSIG</a> (free)\n" +
                "</ul>\n");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }
        endHtmlWriter(out, writer, tErddapUrl, false);

    }


    /**
     * Direct a WMS request to proper handler.
     *
     * <p>This assumes request was for /erddap/wms
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    ("wms") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     */
    public void doWms(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, int datasetIDStartsAt, String userQuery) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);
        int slashPo = endOfRequestUrl.indexOf('/'); //between datasetID/endEnd
        if (slashPo < 0) slashPo = endOfRequestUrl.length();
        String tDatasetID = endOfRequestUrl.substring(0, slashPo);
        String endEnd = slashPo >= endOfRequestUrl.length()? "" : 
            endOfRequestUrl.substring(slashPo + 1);

        //catch other reponses outside of try/catch  (so errors handled in doGet)
        if (endOfRequestUrl.equals("") || endOfRequestUrl.equals("index.htm")) {
            response.sendRedirect(tErddapUrl + "/wms/index.html");
            return;
        }
        if (endEnd.length() == 0 && endOfRequestUrl.startsWith("index.")) {
            sendDatasetList(request, response, loggedInAs, "wms", endOfRequestUrl.substring(5)); 
            return;
        }
        if (endOfRequestUrl.equals("documentation.html")) {
            doWmsDocumentation(request, response, loggedInAs);
            return;
        }
        if (endOfRequestUrl.equals("openlayers110.html")) { 
            doWmsOpenLayers(request, response, loggedInAs, "1.1.0", EDStatic.wmsSampleDatasetID);
            return;
        }
        if (endOfRequestUrl.equals("openlayers111.html")) { 
            doWmsOpenLayers(request, response, loggedInAs, "1.1.1", EDStatic.wmsSampleDatasetID);
            return;
        }
        if (endOfRequestUrl.equals("openlayers130.html")) { 
            doWmsOpenLayers(request, response, loggedInAs, "1.3.0", EDStatic.wmsSampleDatasetID);
            return;
        }

        //if (endOfRequestUrl.equals(WMS_SERVER)) {
        //    doWmsRequest(request, response, loggedInAs, "", userQuery); //all datasets
        //    return;
        //}
        
        //for a specific dataset
        EDDGrid eddGrid = (EDDGrid)gridDatasetHashMap.get(tDatasetID);
        if (eddGrid != null) {
            if (!eddGrid.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { //listPrivateDatasets doesn't apply
                EDStatic.redirectToLogin(loggedInAs, response, tDatasetID);
                return;
            }

            //request is for /wms/datasetID/...
            if (endEnd.equals("") || endEnd.equals("index.htm")) {
                response.sendRedirect(tErddapUrl + "/wms/index.html");
                return;
            }

            if (endEnd.equals("index.html")) {
                doWmsOpenLayers(request, response, loggedInAs, "1.3.0", tDatasetID);
                return;
            }
            if (endEnd.equals(WMS_SERVER)) {
                //if eddGrid instanceof EDDGridFromErddap, redirect the request
                if (eddGrid instanceof EDDGridFromErddap && 
                    //earlier versions of wms work ~differently
                    ((EDDGridFromErddap)eddGrid).sourceErddapVersion() >= 1.23 && 
                    userQuery != null) {
                    //http://coastwatch.pfeg.noaa.gov/erddap/wms/erdMHchla8day/request?
                    //EXCEPTIONS=INIMAGE&VERSION=1.3.0&SRS=EPSG%3A4326&LAYERS=erdMHchla8day
                    //%3Achlorophyll&TIME=2010-07-24T00%3A00%3A00Z&ELEVATION=0.0
                    //&TRANSPARENT=true&BGCOLOR=0x808080&FORMAT=image%2Fpng&SERVICE=WMS
                    //&REQUEST=GetMap&STYLES=&BBOX=307.2,-90,460.8,63.6&WIDTH=256&HEIGHT=256
                    EDDGridFromErddap fe = (EDDGridFromErddap)eddGrid;
                    //tUrl  e.g. http://coastwatch.pfeg.noaa.gov/erddap/griddap/erdMhchla8day
                    String tUrl = fe.getPublicSourceErddapUrl();
                    String sourceDatasetID = File2.getNameNoExtension(tUrl);
                    //!this is good but imperfect because fe.datasetID %3A may be part of some other part of the request
                    //handle percent encoded or not
                    String tQuery = String2.replaceAll(userQuery, fe.datasetID() + "%3A", sourceDatasetID + "%3A");
                    tQuery =        String2.replaceAll(tQuery,    fe.datasetID() + ":",   sourceDatasetID + ":");
                    tUrl = String2.replaceAll(tUrl, "/griddap/", "/wms/") + "/" + WMS_SERVER + 
                        "?" + tQuery;
                    if (verbose) String2.log("redirected to " + tUrl);
                    response.sendRedirect(tUrl);  
                    return;
                }

                doWmsRequest(request, response, loggedInAs, tDatasetID, userQuery); 
                return;
            }

            //error
            sendResourceNotFoundError(request, response, "");
            return;
        } 

        //error
        sendResourceNotFoundError(request, response, "");
    }

    /**
     * This handles a request for the /wms/request or /wms/datasetID/request -- a real WMS service request.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param tDatasetID   an EDDGrid datasetID 
     * @param userQuery post '?', still percentEncoded, may be null.
     */
    public void doWmsRequest(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, String tDatasetID, String userQuery) throws Throwable {

        try {

            //parse userQuery  e.g., ?service=WMS&request=GetCapabilities
            HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, true); //true=names toLowerCase

            //must be service=WMS     but I don't require it
            String tService = queryMap.get("service");
            //if (tService == null || !tService.equals("WMS"))
            //    throw new SimpleException("Query error: service='" + tService + "' must be 'WMS'."); 

            //deal with different request=
            String tRequest = queryMap.get("request");
            if (tRequest == null)
                tRequest = "";

            //e.g., ?service=WMS&request=GetCapabilities
            if (tRequest.equals("GetCapabilities")) {
                doWmsGetCapabilities(request, response, loggedInAs, tDatasetID, queryMap); 
                return;
            }
            
            if (tRequest.equals("GetMap")) {
                doWmsGetMap(request, response, loggedInAs, queryMap); 
                return;
            }

            //if (tRequest.equals("GetFeatureInfo")) { //optional, not yet supported

            throw new SimpleException("Query error: request='" + tRequest + "' isn't supported."); 

        } catch (Throwable t) {

            String2.log("  doWms caught Exception:\n" + MustBe.throwableToString(t));

            //catch errors after the response has begun
            if (neededToSendErrorCode(request, response, t))
                return;

            //send out WMS XML error
            OutputStreamSource outSource = new OutputStreamFromHttpResponse(
                request, response, "error", //fileName is not used
                ".xml", ".xml");
            OutputStream out = outSource.outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");

            //see WMS 1.3.0 spec, section H.2
            String error = MustBe.getShortErrorMessage(t);
            writer.write(
"<?xml version='1.0' encoding=\"UTF-8\"?>\n" +
"<ServiceExceptionReport version=\"1.3.0\"\n" +
"  xmlns=\"http://www.opengis.net/ogc\"\n" +
"  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
"  xsi:schemaLocation=\"http://www.opengis.net/ogc http://schemas.opengis.net/wms/1.3.0/exceptions_1_3_0.xsd\">\n" +
"  <ServiceException" + // code=\"InvalidUpdateSequence\"    ???list of codes
//security: encodeAsXml important to prevent xml injection
">" + XML.encodeAsXML(error) + "</ServiceException>\n" + 
"</ServiceExceptionReport>\n");

            //essential
            writer.flush();
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 

        }
    }


    /**
     * This responds by sending out the WMS html documentation page (long description).
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     */
    public void doWmsDocumentation(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs) throws Throwable {
       
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String e0 = tErddapUrl + "/wms/" + EDStatic.wmsSampleDatasetID + "/" + WMS_SERVER + "?"; 
        String ec = "service=WMS&amp;request=GetCapabilities&amp;version=";
        String e1 = "service=WMS&amp;version="; 
        String e2 = "&amp;request=GetMap&amp;bbox=" + EDStatic.wmsSampleBBox +
                    "&amp;"; //needs c or s
        //this section of code is in 2 places
        int bbox[] = String2.toIntArray(String2.split(EDStatic.wmsSampleBBox, ',')); 
        int tHeight = Math2.roundToInt(((bbox[3] - bbox[1]) * 360) / Math.max(1, bbox[2] - bbox[0]));
        tHeight = Math2.minMaxDef(10, 600, 180, tHeight);
        String e2b = "rs=EPSG:4326&amp;width=360&amp;height=" + tHeight + 
            "&amp;bgcolor=0x808080&amp;layers=";
        //Land,erdBAssta5day:sst,Coastlines,LakesAndRivers,Nations,States
        String e3 = EDStatic.wmsSampleDatasetID + WMS_SEPARATOR + EDStatic.wmsSampleVariable;
        String e4 = "&amp;styles=&amp;format=image/png";
        String et = "&amp;transparent=TRUE";

        String tWmsGetCapabilities110    = e0 + ec + "1.1.0";
        String tWmsGetCapabilities111    = e0 + ec + "1.1.1";
        String tWmsGetCapabilities130    = e0 + ec + "1.3.0";
        String tWmsOpaqueExample110      = e0 + e1 + "1.1.0" + e2 + "s" + e2b + "Land," + e3 + ",Coastlines,Nations" + e4;
        String tWmsOpaqueExample111      = e0 + e1 + "1.1.1" + e2 + "s" + e2b + "Land," + e3 + ",Coastlines,Nations" + e4;
        String tWmsOpaqueExample130      = e0 + e1 + "1.3.0" + e2 + "c" + e2b + "Land," + e3 + ",Coastlines,Nations" + e4;
        String tWmsTransparentExample110 = e0 + e1 + "1.1.0" + e2 + "s" + e2b + e3 + e4 + et;
        String tWmsTransparentExample111 = e0 + e1 + "1.1.1" + e2 + "s" + e2b + e3 + e4 + et;
        String tWmsTransparentExample130 = e0 + e1 + "1.3.0" + e2 + "c" + e2b + e3 + e4 + et;

        //What is WMS?   (generic) 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "WMS Documentation", out);
        try {
            String likeThis = "<a href=\"" + tErddapUrl + "/wms/" + EDStatic.wmsSampleDatasetID + 
                     "/index.html\">like this</a>";
            String makeAGraphRef = "<a href=\"http://coastwatch.pfeg.noaa.gov/erddap/images/embed.html\">Make A Graph</a>\n";
            String datasetListRef = "<p>See the <a href=\"" + tErddapUrl + 
                "/wms/index.html\">list of datasets available via WMS</a> at this ERDDAP installation.\n";
            String makeAGraphListRef =
                "  <br>See the <a href=\"" + tErddapUrl + 
                   "/info/index.html\">list of datasets with Make A Graph</a> at this ERDDAP installation.\n";

            writer.write(
                //see almost identical documentation at ...
                EDStatic.youAreHere(loggedInAs, "wms", "Documentation") +
                EDStatic.wmsLongDescriptionHtml + "\n" +
                datasetListRef +
                //"<p>\n" +
                "<h2>Three Ways to Make Maps with WMS</h2>\n" +
                "<ol>\n" +
                "<li> <b>In theory, anyone can download, install, and use WMS client software.</b>\n" +
                "  <br>Some clients are: \n" +
                "    <a href=\"http://www.esri.com/software/arcgis/\">ArcGIS</a>,\n" +
                "    <a href=\"http://mapserver.refractions.net/phpwms/phpwms-cvs/\">Refractions PHP WMS Client</a>, and\n" +
                "    <a href=\"http://udig.refractions.net//\">uDig</a>. \n" +
                "  <br>To make these work, you would install the software on your computer.\n" +
                "  <br>Then, you would enter the URL of the WMS service into the client.\n" +
                //arcGis required WMS 1.1.1 (1.1.0 and 1.3.0 didn't work)
                "  <br>For example, in ArcGIS (not yet fully working because it doesn't handle time!), use\n" +
                "  <br>\"Arc Catalog : Add Service : Arc Catalog Servers Folder : GIS Servers : Add WMS Server\".\n" +
                "  <br>In ERDDAP, each dataset has its own WMS service, which is located at\n" +
                "  <br>&nbsp; &nbsp; " + tErddapUrl + "/wms/<i>datasetID</i>/" + WMS_SERVER + "?\n" +  
                "  <br>&nbsp; &nbsp; For example: <b>" + e0 + "</b>\n" +  
                "  <br>(Some WMS client programs don't want the <b>?</b> at the end of that URL.)\n" +
                datasetListRef +
                "  <p><b>In practice,</b> we haven't found any WMS clients that properly handle dimensions\n" +
                "  <br>other than longitude and latitude (e.g., time), a feature which is specified by the WMS\n" +
                "  <br>specification and which is utilized by most datasets in ERDDAP's WMS servers.\n" +
                "  <br>You may find that using a dataset's " + makeAGraphRef + 
                "     form and selecting the .kml file type\n" +
                "  <br>(an OGC standard) to load images into <a href=\"http://earth.google.com/\">Google Earth</a> provides\n" +            
                "    a good (non-WMS) map client.\n" +
                makeAGraphListRef +
                "  <br>&nbsp;\n" +
                "<li> <b>Web page authors can embed a WMS client in a web page.</b>\n" +
                "  <br>For example, ERDDAP uses \n" +
                "    <a href=\"http://openlayers.org\">OpenLayers</a>, \n" +  
                "    which is a very versatile WMS client, for the WMS\n" +
                "  <br>page for each ERDDAP dataset \n" +
                "    (" + likeThis + ").\n" +  
                datasetListRef +
                "  <br>OpenLayers doesn't automatically deal with dimensions other than longitude and latitude\n" +            
                "  <br>(e.g., time), so you will have to write JavaScript (or other scripting code) to do that.\n" +
                "  <br>(Adventurous JavaScript programmers can look at the Source Code from a web page " + likeThis + ".)\n" + 
                "  <br>&nbsp;\n" +
                "<li> <b>A person with a browser or a computer program can generate special WMS URLs.</b>\n" +
                "  <br>For example:\n" +
                "  <ul>\n" +
                "  <li>To get an image with a map with an opaque background:\n" +
                "    <br><a href=\"" + tWmsOpaqueExample130 + "\">" + 
                                       tWmsOpaqueExample130 + "</a>\n" +
                "  <li>To get an image with a map with a transparent background:\n" +
                "    <br><a href=\"" + tWmsTransparentExample130 + "\">" + 
                                       tWmsTransparentExample130 + "</a>\n" +
                "  </ul>\n" +
                datasetListRef +
                "  <br><b>See the details below.</b>\n" +
                "  <p><b>In practice, it is probably easier and more versatile to use a dataset's\n" +
                "    " + makeAGraphRef + " web page</b>\n" +
                "  <br>than to use WMS for this purpose.\n" +
                makeAGraphListRef +
                "</ol>\n" +
                "\n");

            //GetCapabilities
            writer.write(
                "<h2><a name=\"GetCapabilities\">Forming GetCapabilities URLs</a></h2>\n" +
                "A GetCapabilities request returns an XML document which provides background information\n" +
                "  <br>about the service and basic information about all of the data available from this\n" +
                "  <br>service. For this dataset, for WMS version 1.3.0, use\n" + 
                "  <br><a href=\"" + tWmsGetCapabilities130 + "\">\n" + 
                    tWmsGetCapabilities130 + "</a>\n" +
                "  <p>The parameters for a GetCapabilities request are:\n" +
                "<table class=\"erd commonBGColor\" cellspacing=\"4\">\n" +
                "  <tr>\n" +
                "    <th nowrap><i>name=value</i><sup>*</sup></th>\n" +
                "    <th>Description</th>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td nowrap>service=WMS</td>\n" +
                "    <td>Required.</td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td nowrap>version=<i>version</i></td>\n" +
                "    <td>Currently, ERDDAP's WMS supports \"1.1.0\", \"1.1.1\", and \"1.3.0\".\n" +
                "      <br>This parameter is optional. The default is \"1.3.0\".</td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td nowrap>request=GetCapabilities</td>\n" +
                "    <td>Required.</td>\n" +
                "  </tr>\n" +
                "  </table>\n" +
                "  <sup>*</sup> Parameter names are case-insensitive.\n" +
                "  <br>Parameter values are case sensitive and must be\n" +
                "    <a href=\"http://en.wikipedia.org/wiki/Percent-encoding\">percent encoded</a>,\n" +
                "    which your browser normally handles for you.\n" +
                "  <br>The parameters may be in any order in the URL.\n" +
                "  <br>&nbsp;\n" +
                "\n");

            //getMap
            writer.write(
                "<h2><a name=\"GetMap\">Forming GetMap URLs</a></h2>\n" +
                "  A person with a browser or a computer program can generate a special URL to request a map.\n" + 
                "  <br>The URL must be in the form\n" +
                "  <br>&nbsp;&nbsp;&nbsp;" + tErddapUrl + "/wms/<i>datasetID</i>/" + WMS_SERVER + "?<i>query</i> " +
                "  <br>The query for a WMS GetMap request consists of several <i>parameterName=value</i>, separated by '&amp;'.\n" +
                "  <br>For example,\n" +
                "  <br>&nbsp; &nbsp; <a href=\"" + tWmsOpaqueExample130 + "\">" + 
                                                   tWmsOpaqueExample130 + "</a>\n" +
                "  <br>The <a name=\"parameters\">parameter</a> options for the GetMap request are:\n" +
                "  <br>&nbsp;\n" + //necessary for the blank line before the table (not <p>)
                "<table class=\"erd commonBGColor\" cellspacing=\"0\">\n" +
                "  <tr>\n" +
                "    <th><i>name=value</i><sup>*</sup></th>\n" +
                "    <th>Description</th>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td nowrap>service=WMS</td>\n" +
                "    <td>Required.</td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>version=<i>version</i></td>\n" +
                "    <td>Request version.\n" +
                "      <br>Currently, ERDDAP's WMS supports \"1.1.0\", \"1.1.1\", and \"1.3.0\".  Required.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>request=GetMap</td>\n" +
                "    <td>Request name.  Required.</td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>layers=<i>layer_list</i></td>\n" +
                "    <td>Comma-separated list of one or more map layers.\n" +
                "        <br>Layers are drawn in the order they occur in the list.\n" +
                "        <br>Currently in ERDDAP's WMS, the layer names from datasets are named <i>datasetID</i>" + 
                    WMS_SEPARATOR + "<i>variableName</i> .\n" +
                "        <br>In ERDDAP's WMS, there are five layers not based on ERDDAP datasets:\n" +
                "        <ul>\n" +
                "        <li> \"Land\" may be drawn BEFORE (as an under layer) or AFTER (as a land mask) layers from grid datasets.\n" +
                "        <li> \"Coastlines\" usually should be drawn AFTER layers from grid datasets.\n" +  
                "        <li> \"LakesAndRivers\" draws lakes and rivers. This usually should be drawn AFTER layers from grid datasets.\n" +
                "        <li> \"Nations\" draws national political boundaries. This usually should be drawn AFTER layers from grid datasets.\n" +
                "        <li> \"States\" draws state political boundaries. This usually should be drawn AFTER layers from grid datasets.\n" +
                "        </ul>\n" +                
                "        Required.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>styles=<i>style_list</i></td>\n" +
                "    <td>Comma-separated list of one rendering style per requested layer.\n" +
                "      <br>Currently in ERDDAP's WMS, the only style offered for each layer is the default style,\n" +
                "      <br>which is specified via \"\" (nothing).\n" +
                "      <br>For example, if you request 3 layers, you can use \"styles=,,\".\n" +
                "      <br>Or, even easier, you can request the default style for all layers via \"styles=\".\n" + 
                "      <br>Required.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td nowrap>1.1.0: srs=<i>namespace:identifier</i>" +
                           "<br>1.1.1: srs=<i>namespace:identifier</i>" +
                           "<br>1.3.0: crs=<i>namespace:identifier</i></td>\n" +
                "    <td>Coordinate reference system.\n" +
                "        <br>Currently in ERDDAP's WMS 1.1.0, the only valid SRS is EPSG:4326.\n" +
                "        <br>Currently in ERDDAP's WMS 1.1.1, the only valid SRS is EPSG:4326.\n" +
                "        <br>Currently in ERDDAP's WMS 1.3.0, the only valid CRS's are CRS:84 and EPSG:4326,\n" +
                "        <br>All of those options support longitude from -180 to 180 and latitude -90 to 90.\n" +
                "        <br>Required.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>bbox=<i>minx,miny,maxx,maxy</i></td>\n" +
                "    <td>Bounding box corners (lower left, upper right) in CRS units.\n" +
                "      <br>Required.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>width=<i>output_width</i></td>\n" +
                "    <td>Width in pixels of map picture. Required.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>height=<i>output_height</i></td>\n" +
                "    <td>Height in pixels of map picture. Required.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>format=<i>output_format</i></td>\n" +
                "    <td>Output format of map.  Currently in ERDDAP's WMS, only image/png is valid.\n" +
                "      <br>Required.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>transparent=<i>TRUE|FALSE</i></td>\n" +
                "    <td>Background transparency of map.  Optional (default=FALSE).\n" +
                "      <br>If TRUE, any part of the image using the BGColor will be made transparent.\n" +      
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>bgcolor=<i>color_value</i></td>\n" +
                "    <td>Hexadecimal 0xRRGGBB color value for the background color. Optional (default=0xFFFFFF, white).\n" +
                "      <br>If transparent=true, we recommend bgcolor=0x808080 (gray), since white is in some color palettes.\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>exceptions=<i>exception_format</i></td>\n" +
                "    <td>The format for WMS exception responses.  Optional.\n" +
                "      <br>Currently, ERDDAP's WMS 1.1.0 and 1.1.1 supports\n" +
                "          \"application/vnd.ogc.se_xml\" (the default),\n" +
                "      <br>\"application/vnd.ogc.se_blank\" (a blank image) and\n" +
                "          \"application/vnd.ogc.se_inimage\" (the error in an image).\n" +
                "      <br>Currently, ERDDAP's WMS 1.3.0 supports \"XML\" (the default),\n" +
                "         \"BLANK\" (a blank image), and\n" +
                "      <br>\"INIMAGE\" (the error in an image).\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>time=<i>time</i></td>\n" +
                "    <td>Time value of layer desired, specified in ISO8601 format: yyyy-MM-ddTHH:mm:ssZ .\n" +
                "      <br>Currently in ERDDAP's WMS, you can only specify one time value per request.\n" +
                "      <br>In ERDDAP's WMS, the value nearest to the value you specify (if between min and max) will be used.\n" +
                "      <br>In ERDDAP's WMS, the default value is the last value in the dataset's 1D time array.\n" +
                "      <br>In ERDDAP's WMS, \"current\" is interpreted as the last available time (recent or not).\n" +
                "      <br>Optional (in ERDDAP's WMS, the default is the last value, whether it is recent or not).\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>elevation=<i>elevation</i></td>\n" +
                "    <td>Elevation of layer desired.\n" +
                "      <br>Currently in ERDDAP's WMS, you can only specify one elevation value per request.\n" +
                "      <br>In ERDDAP's WMS, this is used for the altitude dimension (if any). (in meters, positive=up)\n" +
                "      <br>In ERDDAP's WMS, the value nearest to the value you specify (if between min and max) will be used.\n" +
                "      <br>Optional (in ERDDAP's WMS, the default value is the last value in the dataset's 1D altitude array).\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td>dim_<i>name</i>=<i>value</i></td>\n" + //see WMS 1.3.0 spec section C.3.5
                "    <td>Value of other dimensions as appropriate.\n" +
                "      <br>Currently in ERDDAP's WMS, you can only specify one value per dimension per request.\n" +
                "      <br>In ERDDAP's WMS, this is used for the non-time, non-altitude dimensions.\n" +
                "      <br>The name of a dimension will be \"dim_\" plus the dataset's name for the dimension, for example \"dim_model\".\n" +
                "      <br>In ERDDAP's WMS, the value nearest to the value you specify (if between min and max) will be used.\n" +
                "      <br>Optional (in ERDDAP's WMS, the default value is the last value in the dimension's 1D array).\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "</table>\n" +
                //WMS 1.3.0 spec section 6.8.1
                "  <sup>*</sup> Parameter names are case-insensitive.\n" +
                "  <br>Parameter values are case sensitive and must be\n" +
                "    <a href=\"http://en.wikipedia.org/wiki/Percent-encoding\">percent encoded</a>,\n" +
                "    which your browser normally handles for you.\n" +
                "  <br>The parameters may be in any order in the URL.\n" +
                "<p>(Revised from Table 8 of the WMS 1.3.0 specification)\n" +
                "\n");

            //notes
            writer.write(
                "<h3><a name=\"notes\">Notes</a></h3>\n" +
                "<ul>\n" +            
                "<li><b>Grid data layers:</b> In ERDDAP's WMS, all data variables in grid datasets that use\n" +
                "  <br>longitude and latitude dimensions are available via WMS.\n" +
                "  <br>Each such variable is available as a WMS layer, with the name <i>datasetID</i>" + 
                    WMS_SEPARATOR + "<i>variableName</i>.\n" +
                "  <br>Each such layer is transparent (i.e., data values are represented as a range of colors\n" +
                "  <br>and missing values are represented by transparent pixels).\n" +
                "<li><b>Table data layers:</b> Currently in ERDDAP's WMS, data variables in table datasets are\n" +
                "  <br>not available via WMS.\n" +
                "<li><b>Dimensions:</b> A consequence of the WMS design is that the TIME, ELEVATION, and other \n" +
                "  <br>dimension values that you specify in a GetMap request apply to all of the layers.\n" +
                "  <br>There is no way to specify different values for different layers.\n" +
                //"<li><b>Longitude:</b> The supported CRS values only support longitude values from -180 to 180.\n" +
                //"   <br>But some ERDDAP datasets have longitude values 0 to 360.\n" +
                //"   <br>Currently in ERDDAP's WMS, those datasets are only available from longitude 0 to 180 in WMS.\n" +
                "<li><b>Strict?</b> The table above specifies how a client should form a GetMap request.\n" +
                "  <br>In practice, ERDDAP's WMS tries to be as lenient as possible when processing GetMap\n" +
                "  <br>requests, since many current clients don't follow the specification. However, if you\n" +
                "  <br>are forming GetMap URLs, we encourage you to try to follow the specification.\n" +
                "<li><b>Why are there separate WMS servers for each dataset?</b> Because the GetCapabilities\n" +
                "  <br>document lists all values of all dimensions for each dataset, the information for each\n" +
                "  <br>dataset can be voluminous (easily 300 KB). If all the datasets (currently ~300 at the)\n" +
                "  <br>ERDDAP main site were to be included in one WMS, the resulting GetCapabilities document\n" +
                "  <br>would be huge (~90 MB) which would take a long time to download (causing many people\n" +
                "  <br>think something was wrong and give up) and would overwhelm most client software.\n" +
                //"   <br>However, a WMS server with all of this ERDDAP's datasets does exist.  You can access it at\n" +
                //"   <br>" + tErddapUrl + "/wms/" + WMS_SERVER + "?\n" + 
                "</ul>\n");

            writer.write(
                //1.3.0 examples
                "<h2><a name=\"examples\">Examples</a></h2>\n" +
                "<p>ERDDAP is compatible with the current <b>WMS 1.3.0</b> standard.\n" +
                "<table class=\"erd\" cellspacing=\"0\">\n" +
                "  <tr>\n" +
                "    <td><b> GetCapabilities </b></td>\n" +
                "    <td><a href=\"" + tWmsGetCapabilities130 + "\">" + 
                                       tWmsGetCapabilities130 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td><b> GetMap </b><br> (opaque) </td>\n" +
                "    <td><a href=\"" + tWmsOpaqueExample130 + "\">" + 
                                       tWmsOpaqueExample130 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td><b> GetMap </b><br> (transparent) </td>\n" +
                "    <td><a href=\"" + tWmsTransparentExample130 + "\">" + 
                                       tWmsTransparentExample130 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td nowrap><b> In <a href=\"http://openlayers.org\">OpenLayers</a> </b></td> \n" +
                "    <td><a href=\"" + tErddapUrl + "/wms/openlayers130.html\">OpenLayers Example (WMS 1.3.0)</a></td>\n" +  
                "  </tr>\n" +
                "</table>\n" +
                "\n" +

                //1.1.1 examples
                "<br>&nbsp;\n" +
                "<p><a name=\"examples111\">ERDDAP</a> is also compatible with the older\n" +
                "<b>WMS 1.1.1</b> standard, which may be needed when working with older client software.\n" +
                "<table class=\"erd\" cellspacing=\"0\">\n" +
                "  <tr>\n" +
                "    <td><b> GetCapabilities </b></td>\n" +
                "    <td><a href=\"" + tWmsGetCapabilities111 + "\">" + 
                                       tWmsGetCapabilities111 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td><b> GetMap </b><br> (opaque) </td>\n" +
                "    <td><a href=\"" + tWmsOpaqueExample111 + "\">" + 
                                       tWmsOpaqueExample111 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td><b> GetMap </b><br> (transparent) </td>\n" +
                "    <td><a href=\"" + tWmsTransparentExample111 + "\">" + 
                                       tWmsTransparentExample111 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td nowrap><b> In <a href=\"http://openlayers.org\">OpenLayers</a> </b></td> \n" +
                "    <td><a href=\"" + tErddapUrl + "/wms/openlayers111.html\">OpenLayers Example (WMS 1.1.1)</a></td>\n" +  
                "  </tr>\n" +
                "</table>\n" +
                "\n" +

                //1.1.0 examples
                "<br>&nbsp;\n" +
                "<p><a name=\"examples110\">ERDDAP</a> is also compatible with the older\n" +
                "<b>WMS 1.1.0</b> standard, which may be needed when working with older client software.\n" +
                "<table class=\"erd\" cellspacing=\"0\">\n" +
                "  <tr>\n" +
                "    <td><b> GetCapabilities </b></td>\n" +
                "    <td><a href=\"" + tWmsGetCapabilities110 + "\">" + 
                                       tWmsGetCapabilities110 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td><b> GetMap </b><br> (opaque) </td>\n" +
                "    <td><a href=\"" + tWmsOpaqueExample110 + "\">" + 
                                       tWmsOpaqueExample110 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td><b> GetMap </b><br> (transparent) </td>\n" +
                "    <td><a href=\"" + tWmsTransparentExample110 + "\">" + 
                                       tWmsTransparentExample110 + "</a></td>\n" +
                "  </tr>\n" +
                "  <tr>\n" +
                "    <td nowrap><b> In <a href=\"http://openlayers.org\">OpenLayers</a> </b></td> \n" +
                "    <td><a href=\"" + tErddapUrl + "/wms/openlayers110.html\">OpenLayers Example (WMS 1.1.0)</a></td>\n" +  
                "  </tr>\n" +
                "</table>\n" +
                "\n");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        endHtmlWriter(out, writer, tErddapUrl, false);

    }

    /**
     * Respond to WMS GetMap request for doWms.
     *
     * <p>If the request is from one dataset's wms and it's an EDDGridFromErddap, redirect to remote erddap.
     *  Currently, all requests are from one dataset's wms.
     *
     * <p>Similarly, if request if from one dataset's wms, 
     *   this method can cache results in separate dataset directories.
     *   (Which is good, because dataset's cache is emptied when dataset reloaded.)
     *   Otherwise, it uses EDStatic.fullWmsCacheDirectory.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param queryMap has name=value from the url query string.
     *    names are toLowerCase. values are original values.
     */
    public void doWmsGetMap(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, HashMap<String, String> queryMap) throws Throwable {

        String userQuery = request.getQueryString(); //post "?", still encoded, may be null
        if (userQuery == null)
            userQuery = "";
        String fileName = "wms_" + String2.md5Hex12(userQuery); //no extension

        int width = -1, height = -1, bgColori = 0xFFFFFF;
        String format = null, fileTypeName = null, exceptions = null;
        boolean transparent = false;
        OutputStreamSource outputStreamSource = null;
        OutputStream outputStream = null;
        try {

            //find mainDatasetID  (if request is from one dataset's wms)
            //Currently, all requests are from one dataset's wms.
            // http://coastwatch.pfeg.noaa.gov/erddap/wms/erdBAssta5day/WMS_SERVER?service=.....
            String[] requestParts = String2.split(request.getRequestURI(), '/');  //post EDD.baseUrl, pre "?"
            int wmsPart = String2.indexOf(requestParts, "wms");
            String mainDatasetID = null;
            if (wmsPart >= 0 && wmsPart == requestParts.length - 3) { //it exists, and there are two more parts
                mainDatasetID = requestParts[wmsPart + 1];
                EDDGrid eddGrid = (EDDGrid)gridDatasetHashMap.get(mainDatasetID);
                if (eddGrid == null) {
                    mainDatasetID = null; //something else is going on, e.g., wms for all dataset's together
                } else if (!eddGrid.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { //listPrivateDatasets doesn't apply
                    EDStatic.redirectToLogin(loggedInAs, response, mainDatasetID);
                    return;
                } else if (eddGrid instanceof EDDGridFromErddap &&
                    //earlier versions of wms work ~differently
                    ((EDDGridFromErddap)eddGrid).sourceErddapVersion() >= 1.23) {
                    //Redirect to remote erddap if request is from one dataset's wms and it's an EDDGridFromErddap.
                    //tUrl e.g., http://coastwatch.pfeg.noaa.gov/erddap/griddap/erdBAssta5day
                    String tUrl = ((EDDGridFromErddap)eddGrid).getPublicSourceErddapUrl();
                    int gPo = tUrl.indexOf("/griddap/");
                    if (gPo >= 0) {
                        String rDatasetID = tUrl.substring(gPo + "/griddap/".length()); //rDatasetID is at end of tUrl
                        tUrl = String2.replaceAll(tUrl, "/griddap/", "/wms/");
                        StringBuilder etUrl = new StringBuilder(tUrl + "/" + WMS_SERVER + "?");

                        //change request's layers=mainDatasetID:var,mainDatasetID:var 
                        //              to layers=rDatasetID:var,rDatasetID:var
                        if (userQuery != null) {
                            String qParts[] = EDD.getUserQueryParts(userQuery); //always at least 1 part (may be "")
                            for (int qpi = 0; qpi < qParts.length; qpi++) {
                                if (qpi > 0) etUrl.append('&');

                                //this use of replaceAll is perhaps not perfect but very close...
                                //percentDecode parts, so WMS_SEPARATOR and other chars won't be encoded
                                String part = qParts[qpi]; 
                                int epo = part.indexOf('=');
                                if (epo >= 0) {
                                    part = String2.replaceAll(part, 
                                        "=" + mainDatasetID + WMS_SEPARATOR, 
                                        "=" + rDatasetID    + WMS_SEPARATOR);
                                    part = String2.replaceAll(part, 
                                        "," + mainDatasetID + WMS_SEPARATOR, 
                                        "," + rDatasetID    + WMS_SEPARATOR);
                                    //encodedKey = encodedValue
                                    etUrl.append(SSR.minimalPercentEncode(part.substring(0, epo)) + "=" +
                                                 SSR.minimalPercentEncode(part.substring(epo + 1)));
                                } else {
                                    etUrl.append(qParts[qpi]);
                                }
                            }
                        }
                        if (verbose) String2.log("doWmsGetMap redirected to\n" + etUrl.toString()); 
                        response.sendRedirect(etUrl.toString()); 
                        return;
                    } else String2.log("Internal Error: \"/griddap/\" should have been in " +
                        "EDDGridFromErddap.getNextLocalSourceErddapUrl()=" + tUrl + " .");
                }
            }
            EDStatic.tally.add("WMS doWmsGetMap (since last daily report)", mainDatasetID);
            EDStatic.tally.add("WMS doWmsGetMap (since startup)", mainDatasetID);
            if (mainDatasetID != null) 
                fileName = mainDatasetID + "_" + fileName;
            String cacheDir = mainDatasetID == null? EDStatic.fullWmsCacheDirectory :
                EDStatic.fullCacheDirectory + mainDatasetID + "/";            
            if (reallyVerbose) String2.log("doWmsGetMap cacheDir=" + cacheDir);

            //*** get required values   see wms spec, section 7.3.2   queryMap names are toLowerCase
            String tVersion     = queryMap.get("version");
            if (tVersion == null)
                tVersion = "1.3.0";
            if (!tVersion.equals("1.1.0") && !tVersion.equals("1.1.1") && 
                !tVersion.equals("1.3.0"))
                throw new SimpleException("Query error: VERSION=" + tVersion + 
                    " must be '1.1.0', '1.1.1', or '1.3.0'.");

            String layersCsv    = queryMap.get("layers");
            String stylesCsv    = queryMap.get("styles");
            String crs          = queryMap.get("crs");
            if (crs == null) 
                crs             = queryMap.get("srs");
            String bboxCsv      = queryMap.get("bbox");
            width               = String2.parseInt(queryMap.get("width"));
            height              = String2.parseInt(queryMap.get("height"));
            format              = queryMap.get("format");

            //optional values
            String tTransparent = queryMap.get("transparent"); 
            String tBgColor     = queryMap.get("bgcolor");
            exceptions          = queryMap.get("exceptions");
            //+ dimensions   time=, elevation=, ...=  handled below

            //*** validate parameters
            transparent = tTransparent == null? false : 
                String2.parseBoolean(tTransparent);  //e.g., "false"

            bgColori = tBgColor == null || tBgColor.length() != 8 || !tBgColor.startsWith("0x")? 
                0xFFFFFF :
                String2.parseInt(tBgColor); //e.g., "0xFFFFFF"
            if (bgColori == Integer.MAX_VALUE)
                bgColori = 0xFFFFFF;


            //*** throw exceptions related to throwing exceptions 
            //(until exception, width, height, and format are valid, fall back to XML format)

            //convert exceptions to latest format
            String oExceptions = exceptions;
            if (exceptions == null)
                exceptions = "XML";
            if      (exceptions.equals("application/vnd.ogc.se_xml"))     exceptions = "XML";
            else if (exceptions.equals("application/vnd.ogc.se_blank"))   exceptions = "BLANK";
            else if (exceptions.equals("application/vnd.ogc.se_inimage")) exceptions = "INIMAGE";
            if (!exceptions.equals("XML") && 
                !exceptions.equals("BLANK") &&
                !exceptions.equals("INIMAGE")) {
                exceptions = "XML"; //fall back
                if (tVersion.equals("1.1.0") || tVersion.equals("1.1.1"))
                    throw new SimpleException("Query error: EXCEPTIONS=" + oExceptions + 
                        " must be one of 'application/vnd.ogc.se_xml', 'application/vnd.ogc.se_blank', " +
                        "or 'application/vnd.ogc.se_inimage'.");  
                else //1.3.0+
                    throw new SimpleException("Query error: EXCEPTIONS=" + oExceptions + 
                        " must be one of 'XML', 'BLANK', or 'INIMAGE'.");  
            }

            if (width < 2 || width > WMS_MAX_WIDTH) {
                exceptions = "XML"; //fall back
                throw new SimpleException("Query error: WIDTH=" + width + 
                    " must be between 2 and " + WMS_MAX_WIDTH + ".");
            }
            if (height < 2 || height > WMS_MAX_HEIGHT) {
                exceptions = "XML"; //fall back
                throw new SimpleException("Query error: HEIGHT=" + height + 
                    " must be between 2 and " + WMS_MAX_HEIGHT + ".");
            }
            if (format == null || !format.toLowerCase().equals("image/png")) {
                exceptions = "XML"; //fall back
                throw new SimpleException("Query error: FORMAT=" + format +
                    " must be image/png.");
            }
            format = format.toLowerCase();
            fileTypeName = ".png"; 
            String extension = fileTypeName;  //here, not in other situations

            //*** throw Warnings/Exceptions for other params?   (try to be lenient)
            //layers
            String layers[];
            if (layersCsv == null) {
                layers = new String[]{""};
                //it is required and so should be an Exception, 
                //but http://mapserver.refractions.net/phpwms/phpwms-cvs/ (?) doesn't send it sometimes,
                //so treat null as all defaults
                String2.log("WARNING: In the WMS query, LAYERS wasn't specified: " + userQuery);
            } else {
                layers = String2.split(layersCsv, ',');
            }
            if (layers.length > WMS_MAX_LAYERS)
                throw new SimpleException("Query error: the number of LAYERS=" + layers.length +
                    " must not be more than " + WMS_MAX_LAYERS + "."); //should be 1.., but allow 0
            //layers.length is at least 1, but it may be ""

            //Styles,  see WMS 1.3.0 section 7.2.4.6.5 and 7.3.3.4
            if (stylesCsv == null) {
                stylesCsv = "";
                //it is required and so should be an Exception, 
                //but http://mapserver.refractions.net/phpwms/phpwms-cvs/ doesn't send it,
                //so treat null as all defaults
                String2.log("WARNING: In the WMS query, STYLES wasn't specified: " + userQuery);
            }
            if (stylesCsv.length() == 0) //shorthand for all defaults
                stylesCsv = String2.makeString(',', layers.length - 1);
            String styles[] = String2.split(stylesCsv, ',');
            if (layers.length != styles.length)
                throw new SimpleException("Query error: the number of STYLES=" + styles.length +
                    " must equal the number of LAYERS=" + layers.length + ".");

            //CRS or SRS must be present  
            if (crs == null || crs.length() == 0)   //be lenient: default to CRS:84
                crs = "CRS:84";
            if (crs == null || 
                (!crs.equals("CRS:84") && !crs.equals("EPSG:4326"))) 
                throw new SimpleException("Query error: " + 
                    (tVersion.equals("1.1.0") || 
                     tVersion.equals("1.1.1")? 
                    "SRS=" + crs + " must be EPSG:4326." :
                    "SRS=" + crs + " must be EPSG:4326 or CRS:84."));

            //BBOX = minx,miny,maxx,maxy   see wms 1.3.0 spec section 7.3.3.6            
            if (bboxCsv == null || bboxCsv.length() == 0)
                throw new SimpleException("Query error: BBOX must be specified.");
                //bboxCsv = "-180,-90,180,90";  //be lenient, default to full range
            double bbox[] = String2.toDoubleArray(String2.split(bboxCsv, ','));
            if (bbox.length != 4)
                throw new SimpleException("Query error: BBOX length=" + bbox.length + " must be 4.");
            double minx = bbox[0];
            double miny = bbox[1];
            double maxx = bbox[2];
            double maxy = bbox[3];
            if (!Math2.isFinite(minx) || !Math2.isFinite(miny) ||
                !Math2.isFinite(maxx) || !Math2.isFinite(maxy))
                throw new SimpleException("Query error: invalid number in BBOX=" + bboxCsv + ".");
            if (minx >= maxx)
                throw new SimpleException("Query error: BBOX minx=" + minx + " must be < maxx=" + maxx + ".");
            if (miny >= maxy)
                throw new SimpleException("Query error: BBOX miny=" + miny + " must be < maxy=" + maxy + ".");


            //if request is for JUST a transparent, non-data layer, use a _wms/... cache 
            //  so files can be shared by many datasets and no number of files in dataset dir is reduced
            boolean isNonDataLayer = false;
            if (transparent &&
                (layersCsv.equals("Land") || 
                 layersCsv.equals("LandMask") || 
                 layersCsv.equals("Coastlines") || 
                 layersCsv.equals("LakesAndRivers") || 
                 layersCsv.equals("Nations") ||
                 layersCsv.equals("States"))) {

                isNonDataLayer = true;
                //Land/LandMask not distinguished below, so consolidate images
                if (layersCsv.equals("LandMask"))
                    layersCsv = "Land"; 
                cacheDir = EDStatic.fullWmsCacheDirectory + layersCsv + "/"; 
                fileName = layersCsv + "_" + 
                    String2.md5Hex12(bboxCsv + "w" + width + "h" + height);

            }

            //is the image in the cache?
            if (File2.isFile(cacheDir + fileName + extension)) { 
                //touch nonDataLayer files, since they don't change
                if (isNonDataLayer)
                    File2.touch(cacheDir + fileName + extension);

                //write out the image
                outputStreamSource = new OutputStreamFromHttpResponse(request, response, 
                    fileName, fileTypeName, extension);
                doTransfer(request, response, cacheDir, "_wms/", 
                    fileName + extension, outputStreamSource.outputStream("")); 
                return;
            }
            

            //*** params are basically ok; try to make the map
            //make the image
            BufferedImage bufferedImage = new BufferedImage(width, height, 
                BufferedImage.TYPE_INT_ARGB); //I need opacity "A"
            Graphics g = bufferedImage.getGraphics(); 
            Graphics2D g2 = (Graphics2D)g;
            Color bgColor = new Color(0xFF000000 | bgColori); //0xFF000000 makes it opaque
            g.setColor(bgColor);    
            g.fillRect(0, 0, width, height);  

            //add the layers
            String roles[] = EDStatic.getRoles(loggedInAs);
            LAYER:
            for (int layeri = 0; layeri < layers.length; layeri++) {

                //***deal with non-data layers
                if (layers[layeri].equals(""))
                    continue; 
                if (layers[layeri].equals("Land") || 
                    layers[layeri].equals("LandMask") || 
                    layers[layeri].equals("Coastlines") || 
                    layers[layeri].equals("LakesAndRivers") || 
                    layers[layeri].equals("Nations") ||
                    layers[layeri].equals("States")) {
                    SgtMap.makeCleanMap(minx, maxx, miny, maxy, 
                        false,
                        null, 1, 1, 0, null,
                        layers[layeri].equals("Land") || 
                        layers[layeri].equals("LandMask"), //no need to draw it twice; no distinction here
                        layers[layeri].equals("Coastlines"), 
                        layers[layeri].equals("LakesAndRivers")? 
                            SgtMap.STROKE_LAKES_AND_RIVERS : //stroke (not fill) so, e.g., Great Lakes temp data not obscured by lakeColor
                            SgtMap.NO_LAKES_AND_RIVERS,
                        layers[layeri].equals("Nations"), 
                        layers[layeri].equals("States"),
                        g2, width, height,
                        0, 0, width, height);  
                    //String2.log("WMS layeri="+ layeri + " request was for a non-data layer=" + layers[layeri]);
                    continue;
                }

                //*** deal with grid data
                int spo = layers[layeri].indexOf(WMS_SEPARATOR);
                if (spo <= 0 || spo >= layers[layeri].length() - 1)
                    throw new SimpleException("Query error: LAYER=" + layers[layeri] + 
                        " is invalid (invalid separator position).");
                String datasetID = layers[layeri].substring(0, spo);
                String destVar = layers[layeri].substring(spo + 1);
                EDDGrid eddGrid = (EDDGrid)gridDatasetHashMap.get(datasetID);
                if (eddGrid == null)
                    throw new SimpleException("Query error: LAYER=" + layers[layeri] + 
                        " is invalid (dataset not found).");
                if (!eddGrid.isAccessibleTo(roles)) { //listPrivateDatasets doesn't apply
                    EDStatic.redirectToLogin(loggedInAs, response, datasetID);
                    return;
                }
                if (eddGrid.accessibleViaWMS().length() > 0)
                    throw new SimpleException("Query error: LAYER=" + layers[layeri] + 
                        " is invalid (not accessible via WMS).");
                int dvi = String2.indexOf(eddGrid.dataVariableDestinationNames(), destVar);
                if (dvi < 0)
                    throw new SimpleException("Query error: LAYER=" + layers[layeri] + 
                        " is invalid (variable not found).");
                EDV tDataVariable = eddGrid.dataVariables()[dvi];
                if (!tDataVariable.hasColorBarMinMax())
                    throw new SimpleException("Query error: LAYER=" + layers[layeri] + 
                        " is invalid (variable doesn't have valid colorBarMinimum/Maximum).");

                //style  (currently just the default)
                if (!styles[layeri].equals("") && 
                    !styles[layeri].toLowerCase().equals("default")) { //nonstandard?  but allow it
                    throw new SimpleException("Query error: for LAYER=" + layers[layeri] + 
                        ", STYLE=" + styles[layeri] + " is invalid (must be \"\").");
                }

                //get other dimension info
                EDVGridAxis ava[] = eddGrid.axisVariables();
                StringBuilder tQuery = new StringBuilder(destVar);
                for (int avi = 0; avi < ava.length; avi++) {
                    EDVGridAxis av = ava[avi];
                    if (avi == eddGrid.lonIndex()) {
                        if (maxx <= av.destinationMin() ||
                            minx >= av.destinationMax()) {
                            if (reallyVerbose) String2.log("  layer=" + layeri + " rejected because request is out of lon range.");
                            continue LAYER;
                        }
                        int first = av.destinationToClosestSourceIndex(minx);
                        int last = av.destinationToClosestSourceIndex(maxx);
                        if (first > last) {int ti = first; first = last; last = ti;}
                        int stride = DataHelper.findStride(last - first + 1, width);
                        tQuery.append("[" + first + ":" + stride + ":" + last + "]");
                        continue;
                    }

                    if (avi == eddGrid.latIndex()) {
                        if (maxy <= av.destinationMin() ||
                            miny >= av.destinationMax()) {
                            if (reallyVerbose) String2.log("  layer=" + layeri + " rejected because request is out of lat range.");
                            continue LAYER;
                        }
                        int first = av.destinationToClosestSourceIndex(miny);
                        int last = av.destinationToClosestSourceIndex(maxy);
                        if (first > last) {int ti = first; first = last; last = ti;}
                        int stride = DataHelper.findStride(last - first + 1, height);
                        tQuery.append("[" + first + ":" + stride + ":" + last + "]");
                        continue;
                    }

                    //all other axes
                    String tAvName = 
                        avi == eddGrid.altIndex()? "elevation" :
                        avi == eddGrid.timeIndex()? "time" : 
                            "dim_" + ava[avi].destinationName().toLowerCase(); //make it case-insensitive for queryMap.get
                    String tValueS = queryMap.get(tAvName);
                    if (tValueS == null || 
                        (avi == eddGrid.timeIndex() && tValueS.toLowerCase().equals("current")))
                        //default is always the last value
                        tQuery.append("[" + (ava[avi].sourceValues().size() - 1) + "]");
                    else {
                        double tValueD = av.destinationToDouble(tValueS); //needed in particular for iso time -> epoch seconds
                        if (Double.isNaN(tValueD) ||
                            tValueD < av.destinationCoarseMin() ||
                            tValueD > av.destinationCoarseMax()) {
                            if (reallyVerbose) String2.log("  layer=" + layeri + " rejected because tValueD=" + tValueD + 
                                " for " + tAvName);
                            continue LAYER;
                        }
                        int first = av.destinationToClosestSourceIndex(tValueD);
                        tQuery.append("[" + first + "]");
                    }
                }

                //get the data
                GridDataAccessor gda = new GridDataAccessor(
                    eddGrid, 
                    "/erddap/griddap/" + datasetID + ".dods", tQuery.toString(), 
                    false, //Grid needs column-major order
                    true); //convertToNaN
                long requestNL = gda.totalIndex().size();
                EDStatic.ensureArraySizeOkay(requestNL, "doWmsGetMap");
                int nBytesPerElement = 8;
                int requestN = (int)requestNL; //safe since checked by ensureArraySizeOkay above
                EDStatic.ensureMemoryAvailable(requestNL * nBytesPerElement, "doWmsGetMap"); 
                Grid grid = new Grid();
                grid.data = new double[requestN];
                int po = 0;
                while (gda.increment()) 
                    grid.data[po++] = gda.getDataValueAsDouble(0);
                grid.lon = gda.axisValues(eddGrid.lonIndex()).toDoubleArray();
                grid.lat = gda.axisValues(eddGrid.latIndex()).toDoubleArray(); 

                //make the palette
                //I checked hasColorBarMinMax above.
                //Note that EDV checks validity of values.
                double minData = tDataVariable.combinedAttributes().getDouble("colorBarMinimum"); 
                double maxData = tDataVariable.combinedAttributes().getDouble("colorBarMaximum"); 
                String palette = tDataVariable.combinedAttributes().getString("colorBarPalette"); 
                if (String2.indexOf(EDStatic.palettes, palette) < 0)
                    palette = Math2.almostEqual(3, -minData, maxData)? "BlueWhiteRed" : "Rainbow"; 
                boolean paletteContinuous = String2.parseBoolean( //defaults to true
                    tDataVariable.combinedAttributes().getString("colorBarContinuous")); 
                String scale = tDataVariable.combinedAttributes().getString("colorBarScale"); 
                if (String2.indexOf(EDV.VALID_SCALES, scale) < 0)
                    scale = "Linear";
                String cptFullName = CompoundColorMap.makeCPT(EDStatic.fullPaletteDirectory, 
                    palette, scale, minData, maxData, -1, paletteContinuous, 
                    EDStatic.fullCptCacheDirectory);

                //draw the data on the map
                //for now, just cartesian  -- BEWARE: it may be stretched!
                SgtMap.makeCleanMap( 
                    minx, maxx, miny, maxy, 
                    false,
                    grid, 1, 1, 0, cptFullName, 
                    false, false, SgtMap.NO_LAKES_AND_RIVERS, false, false,
                    g2, width, height,
                    0, 0, width, height); 

            }

            //save image as file in cache dir
            //(It saves as temp file, then renames if ok.)
            SgtUtil.saveAsTransparentPng(bufferedImage, 
                transparent? bgColor : null, 
                cacheDir + fileName); 

            //copy image from file to client
            if (reallyVerbose) String2.log("  image created. copying to client: " + fileName + extension);
            outputStreamSource = new OutputStreamFromHttpResponse(request, response, 
                fileName, fileTypeName, extension);
            doTransfer(request, response, cacheDir, "_wms/", 
                fileName + extension, outputStreamSource.outputStream("")); 

        } catch (Throwable t) {
            //deal with the WMS error  
            //exceptions in this block fall to error handling in doWms

            //catch errors after the response has begun
            if (neededToSendErrorCode(request, response, t))
                return;

            if (exceptions == null)
                exceptions = "XML";

            //send INIMAGE or BLANK response   
            //see wms 1.3.0 spec sections 6.9, 6.10 and 7.3.3.11
            if ((width > 0 && width <= WMS_MAX_WIDTH &&
                 height > 0 && height <= WMS_MAX_HEIGHT &&
                 format != null) &&
                (format.equals("image/png")) &&
                (exceptions.equals("INIMAGE") || exceptions.equals("BLANK"))) {

                //since handled here 
                String msg = MustBe.getShortErrorMessage(t);
                String2.log("  doWms caught Exception (sending " + exceptions + "):\n" + 
                    MustBe.throwableToString(t)); //log full message with stack trace

                //make image
                BufferedImage bufferedImage = new BufferedImage(width, height, 
                    BufferedImage.TYPE_INT_ARGB); //I need opacity "A"
                Graphics g = bufferedImage.getGraphics(); 
                Color bgColor = new Color(0xFF000000 | bgColori); //0xFF000000 makes it opaque
                g.setColor(bgColor);
                g.fillRect(0, 0, width, height);  

                //write exception in image   (if not THERE_IS_NO_DATA)
                if (exceptions.equals("INIMAGE") &&
                    msg.indexOf(DataHelper.THERE_IS_NO_DATA) < 0) {
                    int tHeight = 12; //pixels high
                    msg = String2.noLongLines(msg, (width * 10 / 6) / tHeight, "    ");
                    String lines[] = msg.split("\\n"); //not String2.split which trims
                    g.setColor(Color.black);
                    g.setFont(new Font(EDStatic.fontFamily, Font.PLAIN, tHeight));
                    int ty = tHeight * 2;
                    for (int i = 0; i < lines.length; i++) {
                        g.drawString(lines[i], tHeight, ty);
                        ty += tHeight + 2;
                    }                    
                } //else BLANK

                //send image to client  (don't cache it)
                
                //if (format.equals("image/png")) { //currently, just .png
                    fileTypeName = ".png";
                //}
                String extension = fileTypeName;  //here, not in other situations
                if (outputStreamSource == null)
                    outputStreamSource = 
                        new OutputStreamFromHttpResponse(request, response, 
                            fileName, fileTypeName, extension);
                if (outputStream == null)
                    outputStream = outputStreamSource.outputStream("");
                SgtUtil.saveAsTransparentPng(bufferedImage, 
                    transparent? bgColor : null, 
                    outputStream); 

                return;
            } 
            
            //fall back to XML Exception in doWMS   rethrow t, so it is caught by doWms XML exception handler
            throw t;
        }

    }

    /**
     * Respond to WMS GetCapabilities request for doWms.
     * To become a Layer, a grid variable must use evenly-spaced longitude and latitude variables.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param tDatasetID  a specific dataset
     * @param queryMap should have lowercase'd names
     */
    public void doWmsGetCapabilities(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, String tDatasetID, HashMap<String, String> queryMap) throws Throwable {

        //make sure version is unspecified (latest), 1.1.0, 1.1.1, or 1.3.0.
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String tVersion = queryMap.get("version");
        if (tVersion == null)
            tVersion = "1.3.0";
        if (!tVersion.equals("1.1.0") &&
            !tVersion.equals("1.1.1") &&
            !tVersion.equals("1.3.0"))
            throw new SimpleException("In an ERDDAP WMS getCapabilities query, VERSION=" + tVersion + " is not supported.\n");
        String qm = tVersion.equals("1.1.0") || 
                    tVersion.equals("1.1.1")? "" : "?";  //default for 1.3.0+
        String sc = tVersion.equals("1.1.0") || 
                    tVersion.equals("1.1.1")? "S" : "C";  //default for 1.3.0+
        EDStatic.tally.add("WMS doWmsGetCapabilities (since last daily report)", tDatasetID);
        EDStatic.tally.add("WMS doWmsGetCapabilities (since startup)", tDatasetID);

        //*** describe a Layer for each wms-able data variable in each grid dataset
        //Elements must occur in proper sequence
        boolean firstDataset = true;
        boolean pm180 = true;
        String roles[] = EDStatic.getRoles(loggedInAs);
        EDDGrid eddGrid = (EDDGrid)gridDatasetHashMap.get(tDatasetID);
        if (eddGrid == null) {
            sendResourceNotFoundError(request, response, 
                "Currently, datasetID=" + tDatasetID + " isn't available.");
            return;
        }
        if (!eddGrid.isAccessibleTo(roles)) {
            EDStatic.redirectToLogin(loggedInAs, response, tDatasetID);
            return;
        }
        if (eddGrid.accessibleViaWMS().length() > 0) {
            sendResourceNotFoundError(request, response, eddGrid.accessibleViaWMS());
            return;
        }
        int loni = eddGrid.lonIndex();
        int lati = eddGrid.latIndex();
        EDVGridAxis avs[] = eddGrid.axisVariables();


        //return capabilities xml
        OutputStreamSource outSource = new OutputStreamFromHttpResponse(
            request, response, "Capabilities", ".xml", ".xml");
        OutputStream out = outSource.outputStream("UTF-8");
        Writer writer = new OutputStreamWriter(out, "UTF-8");
        String wmsUrl = tErddapUrl + "/wms/" + tDatasetID + "/" + WMS_SERVER;
        //see the WMS 1.1.0, 1.1.1, and 1.3.0 specification for details 
        //This based example in Annex H.
        if (tVersion.equals("1.1.0"))
            writer.write(
"<?xml version='1.0' encoding=\"UTF-8\" standalone=\"no\" ?>\n" +
"<!DOCTYPE WMT_MS_Capabilities SYSTEM\n" +
"  \"http://schemas.opengis.net/wms/1.1.0/capabilities_1_1_0.dtd\" \n" +
" [\n" +
" <!ELEMENT VendorSpecificCapabilities EMPTY>\n" +
" ]>  <!-- end of DOCTYPE declaration -->\n" +
"<WMT_MS_Capabilities version=\"1.1.0\">\n" +
"  <Service>\n" +
"    <Name>GetMap</Name>\n");  
        else if (tVersion.equals("1.1.1"))
            writer.write(
"<?xml version='1.0' encoding=\"UTF-8\" standalone=\"no\" ?>\n" +
"<!DOCTYPE WMT_MS_Capabilities SYSTEM\n" +
"  \"http://schemas.opengis.net/wms/1.1.1/capabilities_1_1_1.dtd\" \n" +
" [\n" +
" <!ELEMENT VendorSpecificCapabilities EMPTY>\n" +
" ]>  <!-- end of DOCTYPE declaration -->\n" +
"<WMT_MS_Capabilities version=\"1.1.1\">\n" +
"  <Service>\n" +
"    <Name>OGC:WMS</Name>\n");  
        else if (tVersion.equals("1.3.0"))
            writer.write(
"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
//not yet supported: optional updatesequence parameter
"<WMS_Capabilities version=\"1.3.0\" xmlns=\"http://www.opengis.net/wms\"\n" +
"    xmlns:xlink=\"http://www.w3.org/1999/xlink\"\n" +
"    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
"    xsi:schemaLocation=\"http://www.opengis.net/wms http://schemas.opengis.net/wms/1.3.0/capabilities_1_3_0.xsd\">\n" +
"  <Service>\n" +
"    <Name>WMS</Name>\n");

        writer.write(
"    <Title>" + XML.encodeAsXML("WMS for " + eddGrid.title()) + "</Title>\n" +
"    <Abstract>" + XML.encodeAsXML(eddGrid.summary()) + "</Abstract>\n" + 
"    <KeywordList>\n");
        String keywords[] = eddGrid.keywords();
        for (int i = 0; i < keywords.length; i++) 
            writer.write(
"      <Keyword>" + XML.encodeAsXML(keywords[i]) + "</Keyword>\n");
        writer.write(
"    </KeywordList>\n" +
"    <!-- Top-level address of service -->\n" +  //spec annex H has "... or service provider"
"    <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\"\n" +
"       xlink:href=\"" + wmsUrl + qm + "\" />\n" +
"    <ContactInformation>\n" +
"      <ContactPersonPrimary>\n" +
"        <ContactPerson>" + XML.encodeAsXML(EDStatic.adminIndividualName) + "</ContactPerson>\n" +
"        <ContactOrganization>" + XML.encodeAsXML(EDStatic.adminInstitution) + "</ContactOrganization>\n" +
"      </ContactPersonPrimary>\n" +
"      <ContactPosition>" + XML.encodeAsXML(EDStatic.adminPosition) + "</ContactPosition>\n" +
"      <ContactAddress>\n" +
"        <AddressType>postal</AddressType>\n" +
"        <Address>" + XML.encodeAsXML(EDStatic.adminAddress) + "</Address>\n" +
"        <City>" + XML.encodeAsXML(EDStatic.adminCity) + "</City>\n" +
"        <StateOrProvince>" + XML.encodeAsXML(EDStatic.adminStateOrProvince) + "</StateOrProvince>\n" +
"        <PostCode>" + XML.encodeAsXML(EDStatic.adminPostalCode) + "</PostCode>\n" +
"        <Country>" + XML.encodeAsXML(EDStatic.adminCountry) + "</Country>\n" +
"      </ContactAddress>\n" +
"      <ContactVoiceTelephone>" + XML.encodeAsXML(EDStatic.adminPhone) + "</ContactVoiceTelephone>\n" +
"      <ContactElectronicMailAddress>" + XML.encodeAsXML(EDStatic.adminEmail) + "</ContactElectronicMailAddress>\n" +
"    </ContactInformation>\n" +
"    <Fees>" + XML.encodeAsXML(eddGrid.fees()) + "</Fees>\n" +
"    <AccessConstraints>" + XML.encodeAsXML(eddGrid.accessConstraints()) + "</AccessConstraints>\n" +

        (tVersion.equals("1.1.0") || 
         tVersion.equals("1.1.1")? "" :
"    <LayerLimit>" + WMS_MAX_LAYERS + "</LayerLimit>\n" +  
"    <MaxWidth>" + WMS_MAX_WIDTH + "</MaxWidth>\n" +  
"    <MaxHeight>" + WMS_MAX_HEIGHT + "</MaxHeight>\n") +  

"  </Service>\n");

        //Capability
        writer.write(
"  <Capability>\n" +
"    <Request>\n" +
"      <GetCapabilities>\n" +
"        <Format>" + (tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? 
            "application/vnd.ogc.wms_xml" : 
            "text/xml") + 
          "</Format>\n" +
"        <DCPType>\n" +
"          <HTTP>\n" +
"            <Get>\n" +
"              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" \n" +
"                xlink:type=\"simple\" \n" +
"                xlink:href=\"" + wmsUrl + qm + "\" />\n" +
"            </Get>\n" +
"          </HTTP>\n" +
"        </DCPType>\n" +
"      </GetCapabilities>\n" +
"      <GetMap>\n" +
"        <Format>image/png</Format>\n" +
//"        <Format>image/jpeg</Format>\n" +
"        <DCPType>\n" +
"          <HTTP>\n" +
"            <Get>\n" +
"              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" \n" +
"                xlink:type=\"simple\" \n" +
"                xlink:href=\"" + wmsUrl + qm + "\" />\n" +
"            </Get>\n" +
"          </HTTP>\n" +
"        </DCPType>\n" +
"      </GetMap>\n" +
/* GetFeatureInfo is optional; not currently supported.  (1.1.0, 1.1.1 and 1.3.0 vary)
"      <GetFeatureInfo>\n" +
"        <Format>text/xml</Format>\n" +
"        <Format>text/plain</Format>\n" +
"        <Format>text/html</Format>\n" +
"        <DCPType>\n" +
"          <HTTP>\n" +
"            <Get>\n" +
"              <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" \n" +
"                xlink:type=\"simple\" \n" +
"                xlink:href=\"" + wmsUrl + qm + "\" />\n" +
"            </Get>\n" +
"          </HTTP>\n" +
"        </DCPType>\n" +
"      </GetFeatureInfo>\n" +
*/
"    </Request>\n" +
"    <Exception>\n");
if (tVersion.equals("1.1.0") || tVersion.equals("1.1.1")) 
    writer.write(
"      <Format>application/vnd.ogc.se_xml</Format>\n" +
"      <Format>application/vnd.ogc.se_inimage</Format>\n" +  
"      <Format>application/vnd.ogc.se_blank</Format>\n" +
"    </Exception>\n");
else writer.write(
"      <Format>XML</Format>\n" +
"      <Format>INIMAGE</Format>\n" +  
"      <Format>BLANK</Format>\n" +
"    </Exception>\n");

if (tVersion.equals("1.1.0") || tVersion.equals("1.1.1"))
    writer.write(
"    <VendorSpecificCapabilities />\n");

//*** start the outer layer
writer.write(
"    <Layer>\n" + 
"      <Title>" + XML.encodeAsXML(eddGrid.title()) + "</Title>\n");
//?Authority
//?huge bounding box?
//CRS   both CRS:84 and EPSG:4326 are +-180, +-90;     ???other CRSs?
//(tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? "" : "      <CRS>CRS:84</CRS>\n") +
//"      <" + sc + "RS>EPSG:4326</" + sc + "RS>\n" +


        //EEEEK!!!! CRS:84 and EPSG:4326 want lon -180 to 180, but many erddap datasets are 0 to 360.
        //That seems to be ok.   But still limit x to -180 to 360.
        //pre 2009-02-11 was limit x to +/-180.
        double safeMinX = Math.max(-180, avs[loni].destinationMin());
        double safeMinY = Math.max( -90, avs[lati].destinationMin());
        double safeMaxX = Math.min( 360, avs[loni].destinationMax());
        double safeMaxY = Math.min(  90, avs[lati].destinationMax());

        //*** firstDataset, describe the LandMask non-data layer 
        if (firstDataset) {
            firstDataset = false;
            pm180 = safeMaxX < 181; //crude
            addWmsNonDataLayer(writer, tVersion, 0, 0, pm180); 
        }

        //Layer for the dataset
        //Elements are in order of elements described in spec.
        writer.write(
   "      <Layer>\n" +
   "        <Title>" + XML.encodeAsXML(eddGrid.title()) + "</Title>\n" +

        //?optional Abstract and KeywordList

        //Style: WMS 1.3.0 section 7.2.4.6.5 says "If only a single style is available, 
        //that style is known as the "default" style and need not be advertised by the server."
        //See also 7.3.3.4.
        //I'll go with that. It's simple.
        //???separate out different palettes?
   //"      <Style>\n" +
   //         //example: Default, Transparent    features use specific colors, e.g., LightBlue, Brown
   //"        <Name>Transparent</Name>\n" +
   //"        <Title>Transparent</Title>\n" +
   //"      </Style>\n" +

   //CRS   both CRS:84 and EPSG:4326 are +-180, +-90;     ???other CRSs?

   (tVersion.equals("1.1.0")? "        <SRS>EPSG:4326</SRS>\n" : // >1? space separate them
    tVersion.equals("1.1.1")? "        <SRS>EPSG:4326</SRS>\n" : // >1? use separate tags
        "        <CRS>CRS:84</CRS>\n" +
        "        <CRS>EPSG:4326</CRS>\n") +

   (tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? 
   "        <LatLonBoundingBox " +
               "minx=\"" + safeMinX + "\" " +
               "miny=\"" + safeMinY + "\" " +
               "maxx=\"" + safeMaxX + "\" " +
               "maxy=\"" + safeMaxY + "\" " +
               "/>\n" :
   "        <EX_GeographicBoundingBox>\n" + 
               //EEEEK!!!! CRS:84 and EPSG:4326 want lon -180 to 180, but many erddap datasets are 0 to 360.
               //That seems to be ok.   But still limit x to -180 to 360.
               //pre 2009-02-11 was limit x to +/-180.
   "          <westBoundLongitude>" + safeMinX + "</westBoundLongitude>\n" +
   "          <eastBoundLongitude>" + safeMaxX + "</eastBoundLongitude>\n" +
   "          <southBoundLatitude>" + safeMinY + "</southBoundLatitude>\n" +
   "          <northBoundLatitude>" + safeMaxY + "</northBoundLatitude>\n" +
   "        </EX_GeographicBoundingBox>\n") +

   "        <BoundingBox " + sc + "RS=\"EPSG:4326\" " +
            "minx=\"" + safeMinX + "\" " +
            "miny=\"" + safeMinY + "\" " +
            "maxx=\"" + safeMaxX + "\" " +
            "maxy=\"" + safeMaxY + "\" " +
            (avs[loni].isEvenlySpaced()? "resx=\"" + Math.abs(avs[loni].averageSpacing()) + "\" " : "") +
            (avs[lati].isEvenlySpaced()? "resy=\"" + Math.abs(avs[lati].averageSpacing()) + "\" " : "") +
            "/>\n");

        //???AuthorityURL

        //?optional MinScaleDenominator and MaxScaleDenominator

        //for 1.1.0 and 1.1.1, make a <Dimension> for each non-lat lon dimension
        // so all <Dimension> elements are together
        if (tVersion.equals("1.1.0") || tVersion.equals("1.1.1")) {
            for (int avi = 0; avi < avs.length; avi++) {
                if (avi == loni || avi == lati)
                    continue;
                EDVGridAxis av = avs[avi];
                String avName = av.destinationName();
                String avUnits = av.units() == null? "" : av.units(); //"" is required by spec if not known (C.2)
                //required by spec (C.2)
                if (avi == eddGrid.timeIndex()) {
                    avName = "time";      
                    avUnits = "ISO8601"; 
                } else if (avi == eddGrid.altIndex())  {
                    avName = "elevation"; 
                    //???is CRS:88 the most appropriate  (see spec 6.7.5 and B.6)
                    //"EPSG:5030" means "meters above the WGS84 ellipsoid."
                    avUnits = "EPSG:5030"; //here just 1.1.0 or 1.1.1
                } else if (EDStatic.units_standard.equals("UDUNITS")) {
                    //convert other udnits to ucum   (this is in WMS GetCapabilities)
                    avUnits = EDUnits.safeUdunitsToUcum(avUnits);
                }

                writer.write(
       "        <Dimension name=\"" + av.destinationName() + "\" " +
                    "units=\"" + avUnits + "\" />\n");
            }
        }


        //the values for each non-lat lon dimension   
        //  for 1.3.0, make a <Dimension>
        //  for 1.1.0 and 1.1.1, make a <Extent> 
        for (int avi = 0; avi < avs.length; avi++) {
            if (avi == loni || avi == lati)
                continue;
            EDVGridAxis av = avs[avi];
            String avName = av.destinationName();
            String avUnits = av.units() == null? "" : av.units(); //"" is required by spec if not known (C.2)
            String unitSymbol = "";
            //required by spec (C.2)
            if (avi == eddGrid.timeIndex()) {
                avName = "time";      
                avUnits = "ISO8601"; 
            } else if (avi == eddGrid.altIndex())  {
                avName = "elevation"; 
                //???is CRS:88 the most appropriate  (see spec 6.7.5 and B.6)
                //"EPSG:5030" means "meters above the WGS84 ellipsoid."
                avUnits = tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? "EPSG:5030" : "CRS:88"; 
                unitSymbol = "unitSymbol=\"m\" "; 
            } else if (EDStatic.units_standard.equals("UDUNITS")) {
                //convert other udnits to ucum (this is in WMS GetCapabilites)
                avUnits = EDUnits.safeUdunitsToUcum(avUnits);
            }

            if (tVersion.equals("1.1.0")) writer.write(
   "        <Extent name=\"" + av.destinationName() + "\" ");
//???nearestValue is important --- validator doesn't like it!!! should be allowed in 1.1.0!!!
//It is described in OGC 01-047r2, section C.3
//  but if I look in 1.1.0 GetCapabilities DTD from http://schemas.opengis.net/wms/1.1.0/capabilities_1_1_0.dtd
//  and look at definition of Extent, there is no mention of multipleValues or nearestValue.
//2008-08-22 I sent email to revisions@opengis.org asking about it
//                    "multipleValues=\"0\" " +  //don't allow request for multiple values    
//                    "nearestValue=\"1\" ");   //do find nearest value                      

            else if (tVersion.equals("1.1.1")) writer.write(
   "        <Extent name=\"" + av.destinationName() + "\" " +
                "multipleValues=\"0\" " +  //don't allow request for multiple values    
                "nearestValue=\"1\" ");   //do find nearest value                      

            else writer.write( //1.3.0+
   "        <Dimension name=\"" + av.destinationName() + "\" " +
                "units=\"" + avUnits + "\" " +
                unitSymbol +
                "multipleValues=\"0\" " +  //don't allow request for multiple values    
                "nearestValue=\"1\" ");   //do find nearest value                       

            writer.write(
                "default=\"" + av.destinationToString(av.lastDestinationValue()) +  "\" " + //default is last value
                //!!!currently, no support for "current" since grid av doesn't have that info to identify if relevant
                //???or just always use last value is "current"???
                ">");

             //extent value(s)
             if (avi != eddGrid.timeIndex() && av.destinationMin() == av.destinationMax()) {
                 // single number  or iso time
                 writer.write(av.destinationMinString()); 
             } else if (avi != eddGrid.timeIndex() && av.isEvenlySpaced()) {
                 //non-time min/max/spacing     
                 writer.write(av.destinationMinString() + "/" + 
                     av.destinationMaxString() + "/" + Math.abs(av.averageSpacing()));
             //} else if (avi == eddGrid.timeIndex() && av.isEvenlySpaced()) {
                 //time min/max/spacing (time always done via iso strings)
                 //!!??? For time, express averageSpacing as ISO time interval, e.g., P1D
                 //Forming them is a little complex, so defer doing it.
             } else {
                 //csv values   (times as iso8601)
                 writer.write(String2.toCSVString(av.destinationStringValues().toStringArray()));
             }

            if (tVersion.equals("1.1.0") || tVersion.equals("1.1.1"))
                writer.write("</Extent>\n");
            else //1.3.0+
                writer.write("</Dimension>\n");
        }

        //?optional MetadataURL   needs to be in standard format (e.g., fgdc)

        writer.write(
   "        <Attribution>\n" +
   "          <Title>" + XML.encodeAsXML(eddGrid.institution()) + "</Title> \n" +
   "          <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" \n" +
   "            xlink:type=\"simple\" \n" +
   "            xlink:href=\"" + XML.encodeAsXML(eddGrid.infoUrl()) + "\" />\n" +
            //LogoURL
   "        </Attribution>\n");

        //?optional Identifier and AuthorityURL
        //?optional FeatureListURL
        //?optional DataURL (tied to a MIME type)
        //?optional LegendURL

/*
        //gather all of the av destinationStringValues
        StringArray avDsv[] = new StringArray[avs.length];
        for (int avi = 0; avi < avs.length; avi++) {
            if (avi == loni || avi == lati)
                continue;
            avDsv[avi] = avs[avi].destinationStringValues();
        }

*/

        //an inner Layer for each dataVariable
        String dvNames[] = eddGrid.dataVariableDestinationNames();
        for (int dvi = 0; dvi < dvNames.length; dvi++) {
            if (!eddGrid.dataVariables()[dvi].hasColorBarMinMax())
                continue;
            writer.write(
   "        <Layer opaque=\"1\" >\n" + //see 7.2.4.7.1  use opaque for grid data, non for table data
   "          <Name>" + XML.encodeAsXML(tDatasetID + WMS_SEPARATOR + dvNames[dvi]) + "</Name>\n" +
   "          <Title>" + XML.encodeAsXML(eddGrid.title() + " - " + dvNames[dvi]) + "</Title>\n");
/*

            //make a sublayer for each index combination  !!!???          
            NDimensionalIndex ndi = new NDimensionalIndex( shape[]);
            int current[] = ndi.getCurrent();
            StringBuilder dims = new StringBuilder();
            while (ndi.increment()) {
                //make the dims string, e.g., !time:2006-08-23T12:00:00Z!elevation:0
                dims.setLength(0);
                for (int avi = 0; avi < avs.length; avi++) {
                    if (avi == loni || avi == lati)
                        continue;
                    dims.append(WMS_SEPARATOR +  ...currentavDsv[avi] = avs[avi].destinationStringValues();
                }
                writer.write
                    "<Layer opaque=\"1\" >\n" + //see 7.2.4.7.1  use opaque for grid data, non for table data
                    "  <Name>" + XML.encodeAsXML(tDatasetID + WMS_SEPARATOR + dvNames[dvi]) + dims + "</Name>\n" +
                    "  <Title>" + XML.encodeAsXML(eddGrid.title() + " - " + dvNames[dvi]) + dims + "</Title>\n");
                    "        </Layer>\n");

            }

*/
            writer.write(
   "        </Layer>\n");
        }

        //end of the dataset's layer
        writer.write(
   "      </Layer>\n");        
        

        //*** describe the non-data layers   Land, Coastlines, LakesAndRivers, Nations, States
        addWmsNonDataLayer(writer, tVersion, 0, 4, pm180); 

        //*** end of the outer layer
        writer.write(
        "    </Layer>\n");        

        writer.write(
       "  </Capability>\n" +
       (tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? 
            "</WMT_MS_Capabilities>\n" : 
            "</WMS_Capabilities>\n"));

        //essential
        writer.flush();
        if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
        out.close(); 
    }


    /** 
     * Add a non-data layer to the writer's GetCapabilities:  
     *  0=Land/LandMask, 1=Coastlines, 2=LakesAndRivers, 3=Nations, 4=States
     */
    private static void addWmsNonDataLayer(Writer writer, String tVersion, 
        int first, int last, boolean pm180) throws Throwable {

        //Elements must occur in proper sequence
        String firstName = first == last && first == 0? "Land" : "LandMask";
        String names[]  = {firstName, "Coastlines", "LakesAndRivers",   "Nations",             "States"};
        String titles[] = {firstName, "Coastlines", "Lakes and Rivers", "National Boundaries", "State Boundaries"};
        String sc = tVersion.equals("1.1.0") || 
                    tVersion.equals("1.1.1")? "S" : "C";  //default for 1.3.0+
        double safeMinX = pm180? -180 : 0;
        double safeMaxX = pm180?  180 : 360;

        for (int layeri = first; layeri <= last; layeri++) {
            writer.write(
"      <Layer" +
     (tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? "" : 
         " opaque=\"" + (layeri == 0? 1 : 0) + "\"") + //see 7.2.4.7.1  use opaque for coverages
     ">\n" + 
"        <Name>"  +  names[layeri] + "</Name>\n" +
"        <Title>" + titles[layeri] + "</Title>\n" +
//?optional Abstract and KeywordList
//don't have to define style if just one

//CRS   both CRS:84 and EPSG:4326 are +-180, +-90;     ???other CRSs?
(tVersion.equals("1.1.0")? "        <SRS>EPSG:4326</SRS>\n" : // >1? space separate them
 tVersion.equals("1.1.1")? "        <SRS>EPSG:4326</SRS>\n" : // >1? use separate tags
     "        <CRS>CRS:84</CRS>\n" +
     "        <CRS>EPSG:4326</CRS>\n") +

(tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? 
"        <LatLonBoundingBox minx=\"" + safeMinX + "\" miny=\"-90\" maxx=\"" + safeMaxX + "\" maxy=\"90\" />\n" :

"        <EX_GeographicBoundingBox>\n" + 
"          <westBoundLongitude>" + safeMinX + "</westBoundLongitude>\n" +
"          <eastBoundLongitude>" + safeMaxX + "</eastBoundLongitude>\n" +
"          <southBoundLatitude>-90</southBoundLatitude>\n" +
"          <northBoundLatitude>90</northBoundLatitude>\n" +
"        </EX_GeographicBoundingBox>\n") +

"        <BoundingBox " + sc + "RS=\"EPSG:4326\" minx=\"" + safeMinX + 
      "\" miny=\"-90\" maxx=\"" + safeMaxX + "\" maxy=\"90\" />\n" +

//???AuthorityURL
//?optional MinScaleDenominator and MaxScaleDenominator
//?optional MetadataURL   needs to be in standard format (e.g., fgdc)

"        <Attribution>\n" +
"          <Title>" + XML.encodeAsXML(
    layeri < 2? "NOAA NOS GSHHS" : "pscoast in GMT"
    ) + "</Title> \n" +
"          <OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" \n" +
"            xlink:type=\"simple\" \n" +
"            xlink:href=\"" + XML.encodeAsXML(
    layeri < 2? "http://www.ngdc.noaa.gov/mgg/shorelines/gshhs.html" : 
                "http://gmt.soest.hawaii.edu/"
    ) + "\" />\n" +
         //LogoURL
"        </Attribution>\n" +

//?optional Identifier and AuthorityURL
//?optional FeatureListURL
//?optional DataURL (tied to a MIME type)
//?optional LegendURL

"      </Layer>\n");        
        }
    }


    /**
     * This responds by sending out the /wms/datasetID/index.html (or 111 or 130) page.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param tVersion the WMS version to use: "1.1.0", "1.1.1" or "1.3.0"
     * @param tDatasetID  currently must be an EDDGrid datasetID, e.g., erdBAssta5day   
     */
    public void doWmsOpenLayers(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, String tVersion, String tDatasetID) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        if (!tVersion.equals("1.1.0") &&
            !tVersion.equals("1.1.1") &&
            !tVersion.equals("1.3.0"))
            throw new SimpleException("WMS version=" + tVersion + " must be " +
                "1.1.0, 1.1.1, or 1.3.0.");            
        EDStatic.tally.add("WMS doWmsOpenLayers (since last daily report)", tDatasetID);
        EDStatic.tally.add("WMS doWmsOpenLayers (since startup)", tDatasetID);

        String csrs = tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? "srs" : "crs";
        String exceptions = tVersion.equals("1.1.0") || tVersion.equals("1.1.1")? 
            "" :  //default is ok for 1.1.0 and 1.1.1
            "exceptions:'INIMAGE', "; 

        EDDGrid eddGrid = (EDDGrid)gridDatasetHashMap.get(tDatasetID);
        if (eddGrid == null) {
            sendResourceNotFoundError(request, response, 
                "Currently, datasetID=" + tDatasetID + " isn't available.");
            return;
        }
        if (!eddGrid.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { //listPrivateDatasets doesn't apply
            EDStatic.redirectToLogin(loggedInAs, response, tDatasetID);
            return;
        }
        int loni = eddGrid.lonIndex();
        int lati = eddGrid.latIndex();
        int alti = eddGrid.altIndex();
        int timei = eddGrid.timeIndex();
        if (loni < 0 || lati < 0) 
            throw new SimpleException("datasetID=" + tDatasetID + 
                " doesn't have longitude and latitude dimensions.");            
        if (eddGrid.accessibleViaWMS().length() > 0)
            throw new SimpleException(eddGrid.accessibleViaWMS());            

        EDVGridAxis gaa[] = eddGrid.axisVariables();
        EDV dva[] = eddGrid.dataVariables();
        String options[][] = new String[gaa.length][];
        String tgaNames[] = new String[gaa.length];
        for (int gai = 0; gai < gaa.length; gai++) {
            if (gai == loni || gai == lati)
                continue;
            options[gai] = gaa[gai].destinationStringValues().toStringArray();
            tgaNames[gai] = gai == alti? "elevation" :
                gai == timei? "time" : "dim_" + gaa[gai].destinationName();
        }
        String baseUrl = tErddapUrl + "/wms/" + tDatasetID;
        String requestUrl = baseUrl + "/" + WMS_SERVER;
      
        String varNames[] = eddGrid.dataVariableDestinationNames();
        int nVars = varNames.length;

        double minX = gaa[loni].destinationMin();
        double maxX = gaa[loni].destinationMax();
        double minY = gaa[lati].destinationMin();
        double maxY = gaa[lati].destinationMax();
        double xRange = maxX - minX;
        double yRange = maxY - minY;
        double centerX = (minX + maxX) / 2;
        boolean pm180 = centerX < 90;
        StringBuilder scripts = new StringBuilder();
        scripts.append(
            //documentation http://dev.openlayers.org/releases/OpenLayers-2.6/doc/apidocs/files/OpenLayers-js.html
            //pre 2010-05-12 this was from www.openlayers.org, but then tiles were jumbled.
            //"<script type=\"text/javascript\" src=\"http://www.openlayers.org/api/OpenLayers.js\"></script>\n" +
            "<script type=\"text/javascript\" src=\"" + tErddapUrl + "/images/openlayers/OpenLayers.js\"></script>\n" +
            "<script type=\"text/javascript\">\n" +
            "  var map; var vLayer=new Array();\n" +
            "  function init(){\n" +
            "    var options = {\n" +
            "      minResolution: \"auto\",\n" +
            "      minExtent: new OpenLayers.Bounds(-1, -1, 1, 1),\n" +
            "      maxResolution: \"auto\",\n" +
            "      maxExtent: new OpenLayers.Bounds(" + 
                //put buffer space around data
                Math.max(pm180? -180 : 0,  minX - xRange/8) + ", " + 
                Math.max(-90,              minY - yRange/8) + ", " +
                Math.min(pm180? 180 : 360, maxX + xRange/8) + ", " + 
                Math.min( 90,              maxY + yRange/8) + ") };\n" +
            "    map = new OpenLayers.Map('map', options);\n" +
            "\n" +
            //"    var ol_wms = new OpenLayers.Layer.WMS( \"OpenLayers WMS\",\n" +
            //"        \"http://labs.metacarta.com/wms/vmap0?\", {layers: 'basic'} );\n" +
            //e.g., ?service=WMS&version=1.3.0&request=GetMap&bbox=0,-75,180,75&crs=EPSG:4326&width=360&height=300
            //    &bgcolor=0x808080&layers=Land,erdBAssta5day:sst,Coastlines,LakesAndRivers,Nations,States&styles=&format=image/png
            //"    <!-- for OpenLayers, always use 'srs'; never 'crs' -->\n" +
            "    var Land = new OpenLayers.Layer.WMS( \"Land\",\n" +
            "        \"" + requestUrl + "?\", \n" +
            "        {" + exceptions + "version:'" + tVersion + "', srs:'EPSG:4326', \n" +
            "          layers:'Land', bgcolor:'0x808080', format:'image/png'} );\n" +
            //2009-06-22 this isn't working, see http://onearth.jpl.nasa.gov/
            //  Their server is overwhelmed. They added an extension to wms for tiled images.
            //  But I can't make OpenLayers support extensions.
            //  For now, remove it.
            //"\n" +
            //"    var jplLayer = new OpenLayers.Layer.WMS( \"NASA Global Mosaic\",\n" +
            //"        \"http://t1.hypercube.telascience.org/cgi-bin/landsat7\", \n" +
            //"        {layers: \"landsat7\"});\n" +
            //"    jplLayer.setVisibility(false);\n" +
            "\n");

        int nLayers = 0;
        for (int dv = 0; dv < nVars; dv++) {
            if (!dva[dv].hasColorBarMinMax())
                continue;
            scripts.append(
            //"        ia_wms = new OpenLayers.Layer.WMS(\"Nexrad\"," +
            //    "\"http://mesonet.agron.iastate.edu/cgi-bin/wms/nexrad/n0r.cgi?\",{layers:\"nexrad-n0r-wmst\"," +
            //    "transparent:true,format:'image/png',time:\"2005-08-29T13:00:00Z\"});\n" +
            "    vLayer[" + nLayers + "] = new OpenLayers.Layer.WMS( \"" + 
                         tDatasetID + WMS_SEPARATOR + varNames[dv] + "\",\n" +
            "        \"" + requestUrl + "?\", \n" +
            "        {" + exceptions + "version:'" + tVersion + "', srs:'EPSG:4326', " +
                         "layers:'" + tDatasetID + WMS_SEPARATOR + varNames[dv] + "', \n" +
            "        ");
            for (int gai = 0; gai < gaa.length; gai++) {
                if (gai == loni || gai == lati)
                    continue;
                scripts.append(
                    tgaNames[gai] + ":'" + 
                    options[gai][options[gai].length - 1] + "', "); //!!!trouble if internal '
            }
            scripts.append("\n" +
            "        transparent:'true', bgcolor:'0x808080', format:'image/png'} );\n" +
            "    vLayer[" + nLayers + "].isBaseLayer=false;\n" +
            "    vLayer[" + nLayers + "].setVisibility(" + (nLayers == 0) + ");\n" +
            "\n");
            nLayers++;
        }

        scripts.append(
            "    var LandMask = new OpenLayers.Layer.WMS( \"Land Mask\",\n" +
            "        \"" + requestUrl + "?\", \n" +
            "        {" + exceptions + "version:'" + tVersion + "', srs:'EPSG:4326', layers:'LandMask', \n" +
            "          bgcolor:'0x808080', format:'image/png', transparent:'true'} );\n" +
            "    LandMask.isBaseLayer=false;\n" +
            "    LandMask.setVisibility(false);\n" +
            "\n" +
            "    var Coastlines = new OpenLayers.Layer.WMS( \"Coastlines\",\n" +
            "        \"" + requestUrl + "?\", \n" +
            "        {" + exceptions + "version:'" + tVersion + "', srs:'EPSG:4326', layers:'Coastlines', \n" +
            "          bgcolor:'0x808080', format:'image/png', transparent:'true'} );\n" +
            "    Coastlines.isBaseLayer=false;\n" +
            "\n" +
            "    var LakesAndRivers = new OpenLayers.Layer.WMS( \"LakesAndRivers\",\n" +
            "        \"" + requestUrl + "?\", \n" +
            "        {" + exceptions + "version:'" + tVersion + "', srs:'EPSG:4326', layers:'LakesAndRivers', \n" +
            "          bgcolor:'0x808080', format:'image/png', transparent:'true'} );\n" +
            "    LakesAndRivers.isBaseLayer=false;\n" +
            "    LakesAndRivers.setVisibility(false);\n" +
            "\n" +
            "    var Nations = new OpenLayers.Layer.WMS( \"National Boundaries\",\n" +
            "        \"" + requestUrl + "?\", \n" +
            "        {" + exceptions + "version:'" + tVersion + "', srs:'EPSG:4326', layers:'Nations', \n" +
            "          bgcolor:'0x808080', format:'image/png', transparent:'true'} );\n" +
            "    Nations.isBaseLayer=false;\n" +
            "\n" +
            "    var States = new OpenLayers.Layer.WMS( \"State Boundaries\",\n" +
            "        \"" + requestUrl + "?\", \n" +
            "        {" + exceptions + "version:'" + tVersion + "', srs:'EPSG:4326', layers:'States', \n" +
            "          bgcolor:'0x808080', format:'image/png', transparent:'true'} );\n" +
            "    States.isBaseLayer=false;\n" +
            "    States.setVisibility(false);\n" +
            "\n");

        scripts.append(
            "    map.addLayers([Land"); //, jplLayer");
        for (int v = 0; v < nLayers; v++) 
            scripts.append(", vLayer[" + v + "]");

        scripts.append(", LandMask, Coastlines, LakesAndRivers, Nations, States]);\n" +  
            "    map.addControl(new OpenLayers.Control.LayerSwitcher());\n" +
            "    map.zoomToMaxExtent();\n" +
            "  }\n");

        for (int gai = 0; gai < gaa.length; gai++) {
            if (gai == loni || gai == lati || options[gai].length <= 1)
                continue;
            scripts.append(
            "  function update" + tgaNames[gai] + "() {\n" +
            "    t = document.f1." + tgaNames[gai] + 
                ".options[document.f1." + tgaNames[gai] + ".selectedIndex].text; \n" +            
            "    for (v=0; v<" + nLayers + "; v++)\n" + 
            "      vLayer[v].mergeNewParams({'" + tgaNames[gai] + "':t});\n" +
            "  }\n");
        }

        scripts.append(
            "</script>\n");

        //*** html head
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = new OutputStreamWriter(out);
        writer.write(EDStatic.startHeadHtml(tErddapUrl, eddGrid.title() + " - WMS"));
        writer.write("\n" + eddGrid.rssHeadLink(loggedInAs));
        writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better
        writer.write(
            "</head>\n");

        //*** html body
        String tBody = String2.replaceAll(EDStatic.startBodyHtml(loggedInAs), "<body", "<body onLoad=\"init()\"");
        String makeAGraphRef = "<a href=\"" + tErddapUrl + "/griddap/" + tDatasetID + ".graph\">Make A Graph</a>";
        writer.write(
            tBody + "\n" +
            HtmlWidgets.htmlTooltipScript(EDStatic.imageDirUrl(loggedInAs)) +
            EDStatic.youAreHere(loggedInAs, "wms", tDatasetID));
        try {
            String queryString = request.getQueryString();
            if (queryString == null)
                queryString = "";
            eddGrid.writeHtmlDatasetInfo(loggedInAs, writer, true, true, true, queryString, "");
            writer.write(
                "&nbsp;\n" + //necessary for the blank line before start of form (not <p>)
                "<form name=\"f1\" action=\"\">\n" +
                "<table border=\"0\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tr>\n" +
                "    <td colspan=\"2\">" +
                    (String2.replaceAll(EDStatic.wmsInstructions, "&wmsVersion;", tVersion)) + 
                    "</td>\n" +
                "  </tr>\n" 
                );

            //a select widget for each axis (but not for lon or lat)
            StringBuilder tAxisConstraints = new StringBuilder();
            for (int gai = 0; gai < gaa.length; gai++) {
                if (gai == loni || gai == lati) {
                    tAxisConstraints.append("[]");
                    continue;
                }
                int nOptions = options[gai].length;
                int nOptionsM1 = nOptions - 1;
                writer.write(   
                "  <tr align=\"left\">\n" +
                "    <td>" + gaa[gai].destinationName() + ":&nbsp;</td>\n" + 
                "    <td width=\"95%\" align=\"left\">");

                //one value: display it
                if (nOptions <= 1) {
                    tAxisConstraints.append("[0]");
                    writer.write(
                        options[gai][0] + //numeric or time so don't need XML.encodeAsHTML
                        "</td>\n");
                    continue;
                }

                //many values: select
                writer.write(
                "      <table cellspacing=\"0\" cellpadding=\"0\">\n" +
                "        <tr>\n" +
                "          <td><select name=\"" + tgaNames[gai] + "\" size=\"1\" title=\"\" " +
                "onChange=\"update" + tgaNames[gai] + "()\" >\n");
                tAxisConstraints.append("[" + nOptionsM1 + "]"); //not ideal; legend not updated by user choice

                for (int i = 0; i < nOptions; i++) 
                    writer.write("<option" + 
                        (i == nOptionsM1? " selected=\"selected\"" : "") + //initial selection
                        ">" + options[gai][i] + "</option>\n"); //numeric or time so don't need XML.encodeAsHTML
                writer.write(
                "            </select></td>\n");

                String axisSelectedIndex = "document.f1." + tgaNames[gai] + ".selectedIndex"; //var name can't have internal "." or "-"
                writer.write(
                "          <td><img src=\"" + EDStatic.imageDirUrl(loggedInAs) + "arrowLL.gif\"  \n" +
                "            title=\"Select the first item.\"   alt=\"&larr;\" \n" +
                "            onMouseUp=\"" + axisSelectedIndex + "=0; update" + tgaNames[gai] + 
                    "();\" ></td>\n" +
                "          <td><img src=\"" + EDStatic.imageDirUrl(loggedInAs) + "minus.gif\"\n" +
                "            title=\"Select the previous item.\"   alt=\"-\" \n" +
                "            onMouseUp=\"" + axisSelectedIndex + "=Math.max(0, " +
                    axisSelectedIndex + "-1); update" + tgaNames[gai] + "();\" ></td>\n" +
                "          <td><img src=\"" + EDStatic.imageDirUrl(loggedInAs) + "plus.gif\"  \n" +
                "            title=\"Select the next item.\"   alt=\"+\" \n" +
                "            onMouseUp=\"" + axisSelectedIndex + "=Math.min(" + nOptionsM1 + 
                   ", " + axisSelectedIndex + "+1); update" + tgaNames[gai] + "();\" ></td>\n" +
                "          <td><img src=\"" + EDStatic.imageDirUrl(loggedInAs) + "arrowRR.gif\" \n" +
                "            title=\"Select the last item.\"   alt=\"&rarr;\" \n" +
                "            onMouseUp=\"" + axisSelectedIndex + "=" + nOptionsM1 + 
                    "; update" + tgaNames[gai] + "();\" ></td>\n" +
                "        </tr>\n" +
                "      </table>\n"); //end of <select> table

                writer.write(
                "    </td>\n" +
                "  </tr>\n");
            } //end of gai loop

            writer.write(
                "</table>\n" +
                "</form>\n" +
                "\n" +
                "&nbsp;\n" + //necessary for the blank line before div (not <p>)
                "<div style=\"width:600px; height:300px\" id=\"map\"></div>\n" +
                "\n");

            //legend for each data var with colorbar info
            for (int dv = 0; dv < nVars; dv++) {
                if (!dva[dv].hasColorBarMinMax())
                    continue;
                writer.write("<p><img src=\"" + tErddapUrl + "/griddap/" + tDatasetID + ".png?" + 
                    dva[dv].destinationName() + 
                    XML.encodeAsHTML(tAxisConstraints.toString() + "&.legend=Only") +
                    "\" alt=\"The legend.\" title=\"The legend. This colorbar is always relevant for " +
                    dva[dv].destinationName() + ", even if the other settings don't match.\"/>\n");
            }

            //flush
            writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better

            //*** What is WMS? 
            String e0 = tErddapUrl + "/wms/" + EDStatic.wmsSampleDatasetID + "/" + WMS_SERVER + "?";
            String ec = "service=WMS&amp;request=GetCapabilities&amp;version=";
            String e1 = "service=WMS&amp;version="; 
            //this section of code is in 2 places
            int bbox[] = String2.toIntArray(String2.split(EDStatic.wmsSampleBBox, ',')); 
            int tHeight = Math2.roundToInt(((bbox[3] - bbox[1]) * 360) / Math.max(1, bbox[2] - bbox[0]));
            tHeight = Math2.minMaxDef(10, 600, 180, tHeight);
            String e2 = "&amp;request=GetMap&amp;bbox=" + EDStatic.wmsSampleBBox +
                        "&amp;" + csrs + "=EPSG:4326&amp;width=360&amp;height=" + tHeight + 
                        "&amp;bgcolor=0x808080&amp;layers=";
            //Land,erdBAssta5day:sst,Coastlines,LakesAndRivers,Nations,States
            String e3 = EDStatic.wmsSampleDatasetID + WMS_SEPARATOR + EDStatic.wmsSampleVariable;
            String e4 = "&amp;styles=&amp;format=image/png";
            String et = "&amp;transparent=TRUE";

            String tWmsOpaqueExample      = e0 + e1 + tVersion + e2 + "Land," + e3 + ",Coastlines,Nations" + e4;
            String tWmsTransparentExample = e0 + e1 + tVersion + e2 +           e3 + e4 + et;
            String datasetListRef = 
                "<br>See the\n" +
                "  <a href=\"" + tErddapUrl + "/wms/index.html\">list \n" +
                "    of datasets available via WMS</a> at this ERDDAP installation.\n";
            String makeAGraphListRef =
                "  <br>See the\n" +
                "    <a href=\"" + tErddapUrl + "/info/index.html\">list \n" +
                "      of datasets with Make A Graph</a> at this ERDDAP installation.\n";

            //What is WMS?   (for tDatasetID) 
            //!!!see the almost identical documentation above
            String wmsUrl = tErddapUrl + "/wms/" + tDatasetID + "/" + WMS_SERVER + "?";
            String capUrl = wmsUrl + "service=WMS&amp;request=GetCapabilities&amp;version=" + tVersion;
            writer.write(
                "<h2><a name=\"description\">What</a> is WMS?</h2>\n" +
                EDStatic.wmsLongDescriptionHtml + "\n" +
                datasetListRef +
                "\n" +
                "<h2>Three Ways to Make Maps with WMS</h2>\n" +
                "<ol>\n" +
                "<li> <b>In theory, anyone can download, install, and use WMS client software.</b>\n" +
                "  <br>Some clients are: \n" +
                "    <a href=\"http://www.esri.com/software/arcgis/\">ArcGIS</a>,\n" +
                "    <a href=\"http://mapserver.refractions.net/phpwms/phpwms-cvs/\">Refractions PHP WMS Client</a>, and\n" +
                "    <a href=\"http://udig.refractions.net//\">uDig</a>. \n" +
                "  <br>To make a client work, you would install the software on your computer.\n" +
                "  <br>Then, you would enter the URL of the WMS service into the client.\n" +
                "  <br>For example, in ArcGIS (not yet fully working because it doesn't handle time!), use\n" +
                "  <br>\"Arc Catalog : Add Service : Arc Catalog Servers Folder : GIS Servers : Add WMS Server\".\n" +
                "  <br>In ERDDAP, this dataset has its own WMS service, which is located at\n" +
                "  <br>" + wmsUrl + "\n" +  
                "  <br>(Some WMS client programs don't want the <b>?</b> at the end of that URL.)\n" +
                datasetListRef +
                "  <p><b>In practice,</b> we haven't found any WMS clients that properly handle dimensions\n" +
                "  <br>other than longitude and latitude (e.g., time), a feature which is specified by the WMS\n" +
                "  <br>specification and which is utilized by most datasets in ERDDAP's WMS servers.\n" +
                "  <br>You may find that using\n" +
                makeAGraphRef + "\n" +
                "    and selecting the .kml file type (an OGC standard)\n" +
                "  <br>to load images into <a href=\"http://earth.google.com/\">Google Earth</a> provides\n" +            
                "     a good (non-WMS) map client.\n" +
                makeAGraphListRef +
                "  <br>&nbsp;\n" +
                "<li> <b>Web page authors can embed a WMS client in a web page.</b>\n" +
                "  <br>For the map above, ERDDAP is using \n" +
                "    <a href=\"http://openlayers.org\">OpenLayers</a>, which is a very versatile WMS client.\n" +
                "  <br>OpenLayers doesn't automatically deal with dimensions other than longitude and latitude\n" +            
                "  <br>(e.g., time), so you will have to write JavaScript (or other scripting code) to do that.\n" +
                "  <br>(Adventurous JavaScript programmers can look at the Souce Code for this web page.)\n" + 
                "  <br>&nbsp;\n" +
                "<li> <b>A person with a browser or a computer program can generate special WMS URLs.</b>\n" +
                "  <br>For example,\n" +
                "  <ul>\n" +
                "  <li>To get the Capabilities XML file, use\n" +
                "    <br><a href=\"" + capUrl + "\">" + capUrl + "</a>\n" +
                "  <li>To get an image file with a map with an opaque background, use\n" +
                "    <br><a href=\"" + tWmsOpaqueExample + "\">" + 
                                       tWmsOpaqueExample + "</a>\n" +
                "  <li>To get an image file with a map with a transparent background, use\n" +
                "    <br><a href=\"" + tWmsTransparentExample + "\">" + 
                                       tWmsTransparentExample + "</a>\n" +
                "  </ul>\n" +
                datasetListRef +
                "  <br><b>For more information about generating WMS URLs, see ERDDAP's \n" +
                "    <a href=\"" +tErddapUrl + "/wms/documentation.html\">WMS Documentation</a> .</b>\n" +
                "  <p><b>In practice, it is probably easier and more versatile to use this dataset's\n" +
                "    " + makeAGraphRef + " web page</b>\n" +
                "  <br>than to use WMS for this purpose.\n" +
                makeAGraphListRef +
                "</ol>\n" +
                "\n");
            
            //"<p>\"<a href=\"http://openlayers.org\">OpenLayers</a> makes it easy to put a dynamic map in any web page.\n" +
            //    "It can display map tiles and markers loaded from any source.\n" +
            //    "OpenLayers is completely free, Open Source JavaScript, released under a BSD-style License. ...\n" +
            //    "OpenLayers is a pure JavaScript library for displaying map data in most modern web browsers,\n" +
            //    "with no server-side dependencies. OpenLayers implements a (still-developing) JavaScript API\n" +
            //    "for building rich web-based geographic applications, similar to the Google Maps and \n" +
            //    "MSN Virtual Earth APIs, with one important difference -- OpenLayers is Free Software, \n" +
            //    "developed for and by the Open Source software community.\" (from the OpenLayers website) \n");

            writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better
            writer.write(scripts.toString());
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        endHtmlWriter(out, writer, tErddapUrl, false);

    }

    /** 
     * This responds to a user's requst for a file in the (psuedo)'protocol' (e.g., images) 
     * directory.
     * This works with files in subdirectories of 'protocol'.
     * <p>The problem is that web.xml transfers all requests to [url]/erddap/*
     * to this servlet. There is no way to allow requests for files in e.g. /images
     * to be handled by Tomcat. So handle them here by doing a simple file transfer.
     *
     * @param protocol here is 'download', 'images', or 'public'
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (e.g., "images") in the requestUrl
     */
    public static void doTransfer(HttpServletRequest request, HttpServletResponse response,
        String protocol, int datasetIDStartsAt) throws Throwable {

        String requestUrl = request.getRequestURI();  // e.g., /erddap/images/QuestionMark.jpg
        String dir = EDStatic.contextDirectory + protocol + "/";
        String fileNameAndExt = requestUrl.length() <= datasetIDStartsAt? "" : 
            requestUrl.substring(datasetIDStartsAt);
        if (!File2.isFile(dir + fileNameAndExt)) {
            sendResourceNotFoundError(request, response, "");
            return;
        }
        String ext = File2.getExtension(fileNameAndExt);
        String fileName = fileNameAndExt.substring(0, fileNameAndExt.length() - ext.length()); 
        OutputStreamSource outSource = new OutputStreamFromHttpResponse(
            request, response, fileName, ext, ext); 
        //characterEncoding not relevant for binary files
        String charEncoding = 
            ext.equals(".asc") || ext.equals(".csv") || 
            ext.equals(".htm") || ext.equals(".html") || 
            ext.equals(".js")  || ext.equals(".json") || ext.equals(".kml") || 
            ext.equals(".pdf") || ext.equals(".tsv") || 
            ext.equals(".txt") || ext.equals(".xml")? 
            "UTF-8" : //an assumption, the most universal solution
            "";

        //Set expires header for things that don't change often.
        //See "High Performance Web Sites" Steve Souders, Ch 3.
        if (protocol.equals("images")) { 
            //&& fileName.indexOf('/') == -1) {   //file not in a subdirectory
            //&& (ext.equals(".gif") || ext.equals(".jpg") || ext.equals(".js") || ext.equals(".png"))) {
            
            GregorianCalendar gc = Calendar2.newGCalendarZulu();
            int nDays = 7; //one week gets most of benefit and few problems
            gc.add(Calendar2.DATE, nDays); 
            String expires = Calendar2.formatAsRFC822GMT(gc);
            if (reallyVerbose) String2.log("  setting expires=" + expires + " header");
     		response.setHeader("Cache-Control", "PUBLIC, max-age=" + 
                (nDays * Calendar2.SECONDS_PER_DAY) + ", must-revalidate");
			response.setHeader("Expires", expires);
        }

        doTransfer(request, response, dir, protocol + "/", fileNameAndExt, 
            outSource.outputStream(charEncoding)); 
    }

    /** 
     * This is the lower level version of doTransfer.
     *
     * @param localDir the actual hard disk directory, ending in '/'
     * @param webDir the apparent directory, ending in '/' (e.g., "public/"),
     *    for error message only
     * @param fileNameAndExt e.g., wms_29847362839.png
     *    (although it can be e.g., /subdir/wms_29847362839.png)
     */
    public static void doTransfer(HttpServletRequest request, HttpServletResponse response,
            String localDir, String webDir, String fileNameAndExt, 
            OutputStream outputStream) throws Throwable {
        if (verbose) String2.log("doTransfer " + localDir + fileNameAndExt);

        //To deal with problems in multithreaded apps 
        //(when deleting and renaming files, for an instant no file with that name exists),
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (File2.isFile(localDir + fileNameAndExt)) {                
                //ok, copy it
                File2.copy(localDir + fileNameAndExt, outputStream);
                outputStream.close();
                return;
            }

            String2.log("WARNING #" + attempt + 
                ": ERDDAP.doTransfer is having trouble. It will try again to transfer " + 
                localDir + fileNameAndExt);
            if (attempt == maxAttempts) {
                //failure
                String2.log("Error: Unable to transfer " + localDir + fileNameAndExt); //localDir
                throw new SimpleException("Error: Unable to transfer " + webDir + fileNameAndExt); //webDir
            } else if (attempt == 1) {
                Math2.gc(1000);  //in File2.delete: gc works better than sleep
            } else {
                Math2.sleep(1000);  //but no need to call gc more than once
            }
        }

    }

    /** 
     * This responds to a user's requst for an rss feed.
     * <br>The login/authentication system does not apply to RSS.
     * <br>The RSS information is always available, for all datasets, to anyone.
     * <br>(This is not ideal.  But otherwise, a user would need to be logged in 
     *   all of the time so that the RSS reader could read the information.)
     * <br>But, since private datasets that aren't accessible aren't advertised, their RSS links are not advertised either.
     *
     * @param protocol here is always 'rss'
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *   in the requestUrl
     */
    public void doRss(HttpServletRequest request, HttpServletResponse response,
        String protocol, int datasetIDStartsAt) throws Throwable {

        String requestUrl = request.getRequestURI();  // /erddap/images/QuestionMark.jpg
        String nameAndExt = requestUrl.length() <= datasetIDStartsAt? "" : 
            requestUrl.substring(datasetIDStartsAt); //should be <datasetID>.rss
        if (!nameAndExt.endsWith(".rss")) {
            sendResourceNotFoundError(request, response, "Invalid name");
            return;
        }
        String name = nameAndExt.substring(0, nameAndExt.length() - 4);
        EDStatic.tally.add("RSS (since last daily report)", name);
        EDStatic.tally.add("RSS (since startup)", name);

        byte rssAr[] = name.length() == 0? null : (byte[])rssHashMap.get(name);
        if (rssAr == null) {
            sendResourceNotFoundError(request, response, "Currently, there is no RSS feed for that name");
            return;
        }
        OutputStreamSource outSource = new OutputStreamFromHttpResponse(
            request, response, name, ".rss", ".rss"); 
        OutputStream outputStream = outSource.outputStream("UTF-8"); 
        outputStream.write(rssAr);
        outputStream.close();
    }


    /**
     * This responds to a setDatasetFlag.txt request.
     *
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doSetDatasetFlag(HttpServletRequest request, HttpServletResponse response, 
        String userQuery) throws Throwable {
        //see also EDD.flagUrl()

        //generate text response
        OutputStreamSource outSource = new OutputStreamFromHttpResponse(
            request, response, "setDatasetFlag", ".txt", ".txt");
        OutputStream out = outSource.outputStream("UTF-8");
        Writer writer = new OutputStreamWriter(out); 

        //look at the request
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, true); //false so names are case insensitive
        String datasetID = queryMap.get("datasetid"); //lowercase name
        String flagKey = queryMap.get("flagkey"); //lowercase name
        //isFileNameSafe is doubly useful: it ensures datasetID could be a dataseID, 
        //  and it ensures file of this name can be created
        String message;
        if (datasetID == null || datasetID.length() == 0 ||
            flagKey == null   || flagKey.length() == 0) {
            message = ERROR + ": Incomplete request.";
        } else if (!String2.isFileNameSafe(datasetID)) {
            message = ERROR + ": Invalid datasetID.";
        } else if (!EDD.flagKey(datasetID).equals(flagKey)) {
            message = ERROR + ": Invalid flagKey.";
        } else {
            //It's ok if it isn't an existing edd.  An inactive dataset is a valid one to flag.
            //And ok of it isn't even in datasets.xml.  Unknown files are removed.
            EDStatic.tally.add("SetDatasetFlag (since startup)", datasetID);
            EDStatic.tally.add("SetDatasetFlag (since last daily report)", datasetID);
            String2.writeToFile(EDStatic.fullResetFlagDirectory + datasetID, datasetID);
            message = "SUCCESS: The flag has been set.";
        }

        writer.write(message);
        if (verbose) String2.log(message);

        //end        
        writer.flush(); //essential
        out.close();         
    }


    /**
     * This responds to a version request.
     *
     * @throws Throwable if trouble
     */
    public void doVersion(HttpServletRequest request, HttpServletResponse response) throws Throwable {
        //see also EDD.flagUrl()

        //generate text response
        OutputStreamSource outSource = new OutputStreamFromHttpResponse(
            request, response, "version", ".txt", ".txt");
        OutputStream out = outSource.outputStream("UTF-8");
        Writer writer = new OutputStreamWriter(out); 
        writer.write("ERDDAP_version=" +EDStatic.erddapVersion + "\n");

        //end        
        writer.flush(); //essential
        out.close();         
    }


    /**
     * This responds to a slidesorter.html request.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doSlideSorter(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, String userQuery) throws Throwable {

        //FUTURE: when submit(), identify the slide acted upon
        //and move it to forefront (zlevel=highest).

        //constants 
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String formName = "f1";
        String dFormName = "document." + formName;
        int border = 20;
        int gap = 10;
        String gapPx = "\"" + gap + "px\"";
        int defaultContentWidth = 360;
        String bgColor = "#ccccff";
        int connTimeout = 120000; //ms
        String ssBePatientAlt = "alt=\"" + EDStatic.ssBePatient + "\" ";

        //DON'T use GET-style params, use POST-style (request.getParameter)  

        //get info from document
        int nSlides = String2.parseInt(request.getParameter("nSlides"));
        int scrollX = String2.parseInt(request.getParameter("scrollX"));
        int scrollY = String2.parseInt(request.getParameter("scrollY"));
        if (nSlides < 0 || nSlides > 250)   nSlides = 250;   //for all of these, consider mv-> MAX_VALUE
        if (scrollX < 0 || scrollX > 10000) scrollX = 0; 
        if (scrollY < 0 || scrollY > 10000) scrollY = 0; 

        //generate html response
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Slide Sorter", out); 
        try {
            writer.write(HtmlWidgets.dragDropScript(EDStatic.imageDirUrl(loggedInAs)));
            writer.write(EDStatic.youAreHereWithHelp(loggedInAs, "Slide Sorter", 
                EDStatic.ssInstructionsHtml)); 

            //begin form
            HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
            widgets.enterTextSubmitsForm = false; 
            writer.write(widgets.beginForm(formName, "POST", //POST, not GET, because there may be lots of text   >1000 char
                tErddapUrl + "/slidesorter.html", "") + "\n");

            //gather slide title, url, x, y
            int newSlide = 0;
            int maxY = 150; //guess at header height
            StringBuilder addToJavaScript = new StringBuilder();
            StringBuilder otherSetDhtml = new StringBuilder();
            for (int oldSlide = 0; oldSlide <= nSlides; oldSlide++) { //yes <=
                String tTitle = oldSlide == nSlides? "" : request.getParameter("title" + oldSlide);
                String tUrl   = oldSlide == nSlides? "" : request.getParameter("url" + oldSlide);
                int tX = String2.parseInt(request.getParameter("x" + oldSlide));
                int tY = String2.parseInt(request.getParameter("y" + oldSlide));
                int tSize = String2.parseInt(request.getParameter("size" + oldSlide));
                if (reallyVerbose) String2.log("  found oldSlide=" + oldSlide + 
                    " title=\"" + tTitle + "\" url=\"" + tUrl + "\" x=" + tX + " y=" + tY);
                tTitle = tTitle == null? "" : tTitle.trim();
                tUrl   = tUrl == null? "" : tUrl.trim();
                String lcUrl = tUrl.toLowerCase();
                if (lcUrl.length() > 0 &&
                    !lcUrl.startsWith("file://") &&
                    !lcUrl.startsWith("ftp://") &&
                    !lcUrl.startsWith("http://") &&
                    !lcUrl.startsWith("https://") &&  //??? will never work?
                    !lcUrl.startsWith("sftp://") &&   //??? will never work?
                    !lcUrl.startsWith("smb://")) {
                    tUrl = "http://" + tUrl;
                    lcUrl = tUrl.toLowerCase();
                }

                //delete this slide if it just has default info
                if (oldSlide < nSlides && 
                    tTitle.length() == 0 &&
                    tUrl.length() == 0)
                    continue;

                //clean up oldSlide's info
                //clean up tSize below
                if (tX < 0 || tX > 3000) tX = 0;
                if (tY < 0 || tY > 20000) tY = maxY;

                //pick apart tUrl
                int qPo = tUrl.indexOf('?');
                if (qPo < 0) qPo = tUrl.length();
                String preQ = tUrl.substring(0, qPo);
                String qAndPost = tUrl.substring(qPo);
                String lcQAndPost = qAndPost.toLowerCase();
                String ext = File2.getExtension(preQ);
                String lcExt = ext.toLowerCase();
                String preExt = preQ.substring(0, preQ.length() - ext.length());
                String pngExts[] = {".smallPng", ".png", ".largePng"};
                int pngSize = String2.indexOf(pngExts, ext);

                //create the slide's content
                String content = ""; //will be html
                int contentWidth = defaultContentWidth;  //default, in px    same size as erddap .png
                int contentHeight = 20;  //default (ok for 1 line of text) in px
                String contentCellStyle = "";

                String dataUrl = null;
                if (tUrl.length() == 0) {
                    if (tSize < 0 || tSize > 2) tSize = 1;
                    contentWidth = defaultContentWidth;
                    contentHeight = 20;                        
                    content = "ERROR: No URL has been specified.\n";

                } else if (lcUrl.startsWith("file://")) {
                    //local file on client's computer
                    //file:// doesn't work in iframe because of security restrictions:
                    //  so server doesn't look at a user's local file.
                    if (tSize < 0 || tSize > 2) tSize = 1;
                    contentWidth = defaultContentWidth;
                    contentHeight = 20;                        
                    content = "ERROR: 'file://' URL's aren't supported (for security reasons).\n";
                
                } else if ((tUrl.indexOf("/tabledap/") > 0 || tUrl.indexOf("/griddap/") > 0) &&
                    (ext.equals(".graph") || pngSize >= 0)) {
                    //Make A Graph
                    //change non-.png file type to .png
                    if (tSize < 0 || tSize > 2) {
                        //if size not specified, try to use pngSize; else tSize=1
                        tSize = pngSize >= 0? pngSize : 1;
                    }
                    ext = pngExts[tSize];
                    tUrl = preExt + pngExts[tSize] + qAndPost;

                    contentWidth  = EDStatic.imageWidths[tSize];
                    contentHeight = EDStatic.imageHeights[tSize];
                    content = "<img src=\"" + XML.encodeAsHTML(tUrl) +  
                        "\" width=\"" + contentWidth + "\" height=\"" + contentHeight + 
                        "\" " + ssBePatientAlt + ">";
                    dataUrl = preExt + ".graph" + qAndPost;

                } else {
                    //all other types
                    if (tSize < 0 || tSize > 2) tSize = 1;

                    /* slideSorter used to contact the url!  That was a security risk. 
                       Don't allow users to tell ERDDAP what URLs to get info from!
                       Don't load user-specified images! (There was a Java bug related to this.)
                    */
                    if (lcExt.equals(".gif") ||
                        lcExt.equals(".png") ||
                        lcExt.equals(".jpeg") ||
                        lcExt.equals(".jpg")) {
                        //give all images a fixed square size  
                        contentWidth  = EDStatic.imageWidths[tSize];
                        contentHeight = contentWidth;
                    } else {
                        //everything else is html content
                        //sizes: small, wide, wide&high
                        contentWidth = EDStatic.imageWidths[tSize == 0? 1 : 2];
                        contentHeight = EDStatic.imageWidths[tSize == 2? 2 : tSize] * 3 / 4; //yes, widths; make wide
                    }
                    content = "<iframe src=\"" + XML.encodeAsHTML(tUrl) + "\" " +
                        "width=\"" + contentWidth + "\" height=\"" + contentHeight + "\" " + 
                        "style=\"background:#FFFFFF\" " +
                        ">Your browser does not support inline frames.</iframe>";

                }

                //write it all
                contentWidth = Math.max(150, contentWidth); //150 so enough for urlTextField+Submit
                int tW = contentWidth + 2 * border;
                writer.write(widgets.hidden("x" + newSlide, "" + tX));
                writer.write(widgets.hidden("y" + newSlide, "" + tY));
                writer.write(widgets.hidden("w" + newSlide, "" + tW));
                //writer.write(widgets.hidden("h" + newSlide, "" + tH));
                writer.write(widgets.hidden("size" + newSlide, "" + tSize));
                writer.write(

                    "<div id=\"div" + newSlide + "\" \n" +
                        "style=\"position:absolute; left:" + tX + "px; top:" + tY + "px; " + //no \n
                        "width:" + tW + "px; " +
                        "border:1px solid #555555; background:" + bgColor + "; overflow:hidden;\"> \n\n" +
                    //top border of gadget
                    "<table bgcolor=\"" + bgColor + "\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                    "<tr><td style=\"width:" + border + "px; height:" + border + "px;\"></td>\n" +
                    "  <td align=\"right\">\n\n");

                if (oldSlide < nSlides) {
                    //table for buttons
                    writer.write(  //width=20 makes it as narrow as possible
                        "  <table width=\"20px\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                        "  <tr>\n\n");

                    //data button
                    if (dataUrl != null) 
                        writer.write(
                        "   <td><img src=\"" + EDStatic.imageDirUrl(loggedInAs) + "data.gif\" alt=\"data\" \n" +
                        "      title=\"Edit the image or download the data in a new browser window.\" \n" +
                        "      style=\"cursor:default;\" \n" +  //cursor:hand doesn't work in Firefox
                        "      onClick=\"window.open('" + dataUrl + "');\" ></td>\n\n"); //open a new window 

                    //resize button
                    writer.write(
                        "   <td><img src=\"" + EDStatic.imageDirUrl(loggedInAs) + "resize.gif\" alt=\"s\" \n" +
                        "      title=\"Change between small/medium/large image sizes.\" \n" +
                        "      style=\"cursor:default;\" \n" +
                        "      onClick=\"" + dFormName + ".size" + newSlide + ".value='" + 
                               (tSize == 2? 0 : tSize + 1) + "'; \n" +
                        "        setHidden(); " + dFormName + ".submit();\"></td>\n\n");

                    //end button's table
                    writer.write(
                        "  </tr>\n" +
                        "  </table>\n\n");

                    //end slide top/center cell; start top/right 
                    writer.write(
                        "  </td>\n" +
                        "  <td style=\"width:" + border + "px; height:" + border + "px;\" align=\"right\">\n");
                }

                //delete button
                if (oldSlide < nSlides) 
                    writer.write(
                    "    <img src=\"" + EDStatic.imageDirUrl(loggedInAs) + "x.gif\" alt=\"x\" \n" +
                    "      title=\"Delete this slide.\" \n" +
                    "      style=\"cursor:default; width:" + border + "px; height:" + border + "px;\"\n" +
                    "      onClick=\"if (confirm('Delete this slide?')) {\n" +
                    "        " + dFormName + ".title" + newSlide + ".value=''; " + 
                                 dFormName + ".url" + newSlide + ".value=''; \n" +
                    "        setHidden(); " + dFormName + ".submit();}\">\n\n");
                writer.write(
                    "  </td>\n" +
                    "</tr>\n\n");

                //Add a Slide
                if (oldSlide == nSlides) 
                    writer.write(
                    "<tr><td>&nbsp;</td>\n" +
                    "  <td align=\"left\" nowrap><b>Add a Slide</b></td>\n" +
                    "</tr>\n\n");

                //gap
                writer.write(
                    "<tr><td height=" + gapPx + "></td>\n" +
                    "  </tr>\n\n");

                //title textfield
                String tPrompt = oldSlide == nSlides? "Title: " : "";
                int tWidth = contentWidth - 7*tPrompt.length() - 6;  // /7px=avg char width   6=border
                writer.write(
                    "<tr><td>&nbsp;</td>\n" +
                    "  <td align=\"left\" nowrap>" + //no \n
                    "<b>" + tPrompt + "</b>");
                writer.write(widgets.textField("title" + newSlide, 
                    "Enter a title for the slide.", 
                    -1, //(contentWidth / 8) - tPrompt.length(),  // /8px=avg bold char width 
                    255, tTitle, 
                    "style=\"width:" + tWidth + "px; background:" + bgColor + "; font-weight:bold;\""));
                writer.write(
                    "</td>\n" + //no space before /td
                    "</tr>\n\n");

                //gap
                writer.write(
                    "<tr><td height=" + gapPx + "></td>\n" +
                    "  </tr>\n\n");

                //content cell
                if (oldSlide < nSlides)
                    writer.write(
                    "<tr><td>&nbsp;</td>\n" +
                    "  <td id=\"cell" + newSlide + "\" align=\"left\" valign=\"top\" " +
                        contentCellStyle +
                        "width=\"" + contentWidth + "\" height=\"" + contentHeight + "\" >" + //no \n
                    content +
                    "</td>\n" + //no space before /td
                    "</tr>\n\n");

                //gap
                if (oldSlide < nSlides)
                    writer.write(
                    "<tr><td height=" + gapPx + "></td>\n" +
                    "  </tr>\n\n");

                //url textfield
                tPrompt = oldSlide == nSlides? "URL:   " : ""; //3 sp make it's length() longer
                tWidth = contentWidth - 7*(tPrompt.length() + 10) - 6;  // /7px=avg char width   //10 for submit  //6=border
                writer.write(
                    "<tr><td>&nbsp;</td>\n" +
                    "  <td align=\"left\" nowrap>" + //no \n
                    "<b>" + tPrompt + "</b>");
                writer.write(widgets.textField("url" + newSlide, 
                    "Enter a URL for the slide from ERDDAP's Make-A-Graph (or any URL).", 
                    -1, //(contentWidth / 7) - (tPrompt.length()-10),  // /7px=avg char width   10 for submit
                    1000, tUrl, 
                    "style=\"width:" + tWidth + "px; background:" + bgColor + "\""));
                //submit button (same row as URL textfield)
                writer.write(widgets.button("button", "submit" + newSlide, 
                    "Click to submit the information on this page to the server.",
                    "Submit",  //button label
                    "style=\"cursor:default;\" onClick=\"setHidden(); " + dFormName + ".submit();\""));
                writer.write(
                    "</td>\n" + //no space before /td
                    "</tr>\n\n");

                //bottom border of gadget
                writer.write(
                    "<tr><td style=\"width:" + border + "px; height:" + border + "px;\"></td></tr>\n" +
                    "</table>\n" +
                    "</div> \n" +
                    "\n");

                maxY = Math.max(maxY, tY + contentHeight + 3 * gap + 6 * border);  //5= 2borders, 1 title, 1 url, 2 dbl gap
                newSlide++;
            }
            writer.write(widgets.hidden("nSlides", "" + newSlide));
            //not important to save scrollXY, but important to have a place for setHidden to store changes
            writer.write(widgets.hidden("scrollX", "" + scrollX)); 
            writer.write(widgets.hidden("scrollY", "" + scrollY));

            //JavaScript
            //setHidden is called by widgets before submit() so position info is stored
            writer.write(
                "<script type=\"text/javascript\">\n" +
                "<!--\n" +
                "function setHidden() { \n" 
                //+ "alert('x0='+ dd.elements.div0.x);"
                );
            for (int i = 0; i < newSlide; i++) 
                writer.write(
                    "try {" +
                    dFormName + ".x" + i + ".value=dd.elements.div" + i + ".x; " +    
                    dFormName + ".y" + i + ".value=dd.elements.div" + i + ".y; " +  
                    dFormName + ".w" + i + ".value=dd.elements.div" + i + ".w; " + 
                    //dFormName + ".h+ " + i + ".value=dd.elements.div" + i + ".h; " +
                    "\n} catch (ex) {if (typeof(console) != 'undefined') console.log(ex.toString());}\n");
            writer.write(
                "try {" +
                dFormName + ".scrollX.value=dd.getScrollX(); " +    
                dFormName + ".scrollY.value=dd.getScrollY(); " +
                "\n} catch (ex) {if (typeof(console) != 'undefined') console.log(ex.toString());}\n" +
                "}\n");
            writer.write(
                "//-->\n" +
                "</script> \n");  

            //make space in document for slides, before end matter
            int nP = (maxY + 30) / 30;  // /30px = avg height of <p>&nbsp;  +30=round up
            for (int i = 0; i < nP; i++) 
                writer.write("<p>&nbsp;\n");
            writer.write("<p>");
            writer.write(widgets.button("button", "submit" + newSlide, 
                "Click to submit the information on this page to the server.",
                "Submit",  //button label
                "style=\"cursor:default;\" onClick=\"setHidden(); " + dFormName + ".submit();\""));
            writer.write(HtmlWidgets.ifJavaScriptDisabled);
            writer.write("<a name=\"instructions\">&nbsp;</a><p>");
            writer.write(EDStatic.ssInstructionsHtml);

            //end form
            writer.write(widgets.endForm());        

            //write the end stuff / set up drag'n'drop
            writer.write(
                "<script type=\"text/javascript\">\n" +
                "<!--\n" +
                "SET_DHTML(CURSOR_MOVE"); //the default cursor for the div's
            for (int i = 0; i < newSlide; i++) 
                writer.write(",\"div" + i + "\""); 
            writer.write(otherSetDhtml.toString() + ");\n");
            for (int i = 0; i < newSlide; i++) 
                writer.write("dd.elements.div" + i + ".setZ(" + i + "); \n");
            writer.write(
                "window.scrollTo(" + scrollX + "," + scrollY + ");\n" +
                addToJavaScript.toString() +
                "//-->\n" +
                "</script>\n");

            //alternatives
            writer.write("\n<hr>\n" +
                "<h2><a name=\"alternatives\">Alternatives to Slide Sorter</a></h2>\n" +
                "<ul>\n" +
                "<li>Web page authors can \n" +
                "  <a href=\"http://coastwatch.pfeg.noaa.gov/erddap/images/embed.html\">embed a graph with the latest data in a web page</a> \n" +
                "  using HTML &lt;img&gt; tags.\n" +
                "<li>Anyone can use or make \n" +
                "  <a href=\"http://coastwatch.pfeg.noaa.gov/erddap/images/gadgets/GoogleGadgets.html\">Google " +
                  "Gadgets</a> to display graphs of the latest data on their iGoogle home page.\n" +
                "</ul>\n" +
                "\n");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end
        endHtmlWriter(out, writer, tErddapUrl, false);
        
    }

    /**
     * This responds to a full text search request.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "search") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     */
    public void doSearch(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, String protocol, int datasetIDStartsAt, String userQuery) throws Throwable {
        
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String fileTypeName = "";
        String searchFor = "";
        String youAreHereTable = 
            //EDStatic.youAreHere(loggedInAs, protocol);

            getYouAreHereTable(
            EDStatic.youAreHere(loggedInAs, protocol),
            //Or, View All Datasets
            "&nbsp;\n" +
            //"<br>Or, <a href=\"" + tErddapUrl + "/info/index.html\">" +
            //EDStatic.viewAllDatasetsHtml + "</a>\n" +
            ////Or, search by category
            //"<p>" + getCategoryLinksHtml(tErddapUrl) +
            ////Use <p> below if other options are enabled.
            "<br>Or, Refine this Search with " + getAdvancedSearchLink(loggedInAs, userQuery) + 
            "&nbsp;&nbsp;&nbsp;");

        try {
            
            //first, always set the standard DAP response header info
            //standardDapHeader(response);

            //respond to search.html request
            String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
                requestUrl.substring(datasetIDStartsAt);

            //redirect to index.html
            if (endOfRequestUrl.equals("") ||
                endOfRequestUrl.equals("index.htm")) {
                response.sendRedirect(tErddapUrl + "/" + protocol + "/index.html");
                return;
            }                    

            //get the 'searchFor' value
            searchFor = request.getParameter("searchFor");
            searchFor = searchFor == null? "" : searchFor.trim();

            fileTypeName = File2.getExtension(endOfRequestUrl); //eg ".html"
            boolean toHtml = fileTypeName.equals(".html");
            if (reallyVerbose) String2.log("  searchFor=" + searchFor + 
                "\n  fileTypeName=" + fileTypeName);
            EDStatic.tally.add("Search For (since startup)", searchFor);
            EDStatic.tally.add("Search For (since last daily report)", searchFor);
            EDStatic.tally.add("Search File Type (since startup)", fileTypeName);
            EDStatic.tally.add("Search File Type (since last daily report)", fileTypeName);

            if (endOfRequestUrl.equals("index.html")) {
                if (searchFor.length() == 0) throw new Exception("show index"); //show form below
                //else handle just below here
            } else if (endsWithPlainFileType(endOfRequestUrl, "index")) {
                if (searchFor == null) {
                    sendResourceNotFoundError(request, response, //or SC_NO_CONTENT error?
                        "An " + requestUrl + 
                        " request must include a query: \"?searchFor=search+words\".");
                    return;
                }
                //else handle just below here
            } else { //usually unsupported fileType
                sendResourceNotFoundError(request, response, "");
                return;
            }

            //do the search
            Table table = getSearchTable(loggedInAs, allDatasetIDs(false), 
                searchFor, toHtml, fileTypeName);

            //show the results as an .html file 
            if (fileTypeName.equals(".html")) { 
                //display start of web page
                OutputStream out = getHtmlOutputStream(request, response);
                Writer writer = getHtmlWriter(loggedInAs, "Search", out); 
                try {
                    //you are here    Search
                    writer.write(youAreHereTable);

                    //display the search form
                    writer.write(getSearchFormHtml(loggedInAs, "<h2>", "</h2>", searchFor));

                    //display datasets
                    writer.write(
                        "<br>&nbsp;\n" +
                        //"<hr>\n" +
                        "<h2>" + EDStatic.resultsOfSearchFor + " <font class=\"highlightColor\">" + 
                        //encodeAsHTML(searchFor) is essential -- to prevent Cross-site-scripting security vulnerability
                        //(which allows hacker to insert his javascript into pages returned by server)
                        //See Tomcat (Definitive Guide) pg 147
                        XML.encodeAsHTML(searchFor) + "</font></h2>\n");  
                    if (table.nRows() == 0) {
                         writer.write("<b>" + XML.encodeAsHTML(EDStatic.THERE_IS_NO_DATA) + "</b>\n" +
                             (searchFor.length() > 0? "<br>" + EDStatic.searchSpelling + "\n" : "") +
                             (searchFor.indexOf(' ') >= 0? "<br>" + EDStatic.searchFewerWords + "\n" : ""));
                    } else {
                        writer.write(
                            //table.nRows() + " " + EDStatic.nDatasetsListed + " " + 
                            table.nRows() + " dataset" + (table.nRows() == 1? " matches" : "s match") + 
                            " the search term(s). " +
                            (table.nRows() == 1? "" : EDStatic.searchRelevantAreFirst) + 
                            //"\n&nbsp;&nbsp;" + 
                            //"(Or, refine this search with " + getAdvancedSearchLink(loggedInAs, userQuery) + ")\n" +
                            "<br>&nbsp;\n" //necessary for the blank line before the table (not <p>)
                            //was "Lower rating numbers indicate a better match.\n" +
                            //+ "<br>" + EDStatic.clickAccessHtml + "\n" +
                            //"<br>&nbsp;\n"
                            );
                        table.saveAsHtmlTable(writer, "commonBGColor", null, 1, false, -1, 
                            false, false); //don't encodeAsHTML the cell's contents, !allowWrap
                    }

                } catch (Throwable t) {
                    writer.write(EDStatic.htmlForException(t));
                }

                //end of document
                endHtmlWriter(out, writer, tErddapUrl, false);
                return;
            }

            //show the results in other file types
            sendPlainTable(loggedInAs, request, response, table, protocol, fileTypeName);
            return;

        } catch (Throwable t) {

            //deal with search error (or just need empty .html searchForm)
            OutputStream out = null;
            Writer writer = null;
            //catch errors after the response has begun
            if (neededToSendErrorCode(request, response, t))
                return;

            if (String2.indexOf(plainFileTypes, fileTypeName) >= 0) 
                //for plainFileTypes, rethrow the error
                throw t;

            //make html page with [error message and] search form
            String error = MustBe.getShortErrorMessage(t);
            out = getHtmlOutputStream(request, response);
            writer = getHtmlWriter(loggedInAs, "Search", out);
            try {
                //you are here      Search
                writer.write(youAreHereTable);

                //write (error and) search form
                if (error.indexOf("show index") < 0) 
                    writeErrorHtml(writer, request, error);
                writer.write(getSearchFormHtml(loggedInAs, "<h2>", "</h2>", searchFor));


            } catch (Throwable t2) {
                writer.write(EDStatic.htmlForException(t2));
            }
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }
    }

    /**
     * This writes the link to Advanced Search page.
     *
     * @param loggedInAs
     * @param paramString the param string 
     *    (starting point for advanced search, already percent encoded) 
     *    (or "" or null).
     */
    public String getAdvancedSearchLink(String loggedInAs, String paramString) throws Throwable {

        return 
            "<a href=\"" + EDStatic.erddapUrl(loggedInAs) + "/search/advanced.html" +
            (paramString == null || paramString.length() == 0? "" : 
                "?" + XML.encodeAsHTML(paramString)) +
            "\">Advanced Search</a>\n" +
            EDStatic.htmlTooltipImage(loggedInAs, EDStatic.advancedSearchHtml) +
            "\n";
    }


    /**
     * This responds to a advanced search request: erddap/search/advanced.html, 
     * and other extensions.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "search") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     */
    public void doAdvancedSearch(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, int datasetIDStartsAt, String userQuery) throws Throwable {
        
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String fileTypeName = "";
        String catAtts[]       = EDStatic.categoryAttributes;
        String catAttsInURLs[] = EDStatic.categoryAttributesInURLs;
        int    nCatAtts = catAtts.length;
        String ANY = "(ANY)";
        String searchFor = "";
          
        //respond to /search/advanced.xxx request
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt); //e.g., advanced.json

        //ensure url is valid  
        if (!endOfRequestUrl.equals("advanced.html") &&
            !endsWithPlainFileType(endOfRequestUrl, "advanced")) {
            //unsupported fileType
            sendResourceNotFoundError(request, response, "");
            return;
        }

        //get the parameters, e.g., the 'searchFor' value
        //parameters are "" if unused (not null)
        searchFor = request.getParameter("searchFor");
        searchFor = searchFor == null? "" : searchFor.trim();
        EDStatic.tally.add("Advanced Search, Search For (since startup)", searchFor);
        EDStatic.tally.add("Advanced Search, Search For (since last daily report)", searchFor);

        //boundingBox
        double minLon = String2.parseDouble(request.getParameter("minLon"));
        double maxLon = String2.parseDouble(request.getParameter("maxLon"));
        double minLat = String2.parseDouble(request.getParameter("minLat"));
        double maxLat = String2.parseDouble(request.getParameter("maxLat"));
        if (!Double.isNaN(minLon) && !Double.isNaN(maxLon) && minLon > maxLon) {
            double td = minLon; minLon = maxLon; maxLon = td; }
        if (!Double.isNaN(minLat) && !Double.isNaN(maxLat) && minLat > maxLat) {
            double td = minLat; minLat = maxLat; maxLat = td; }
        boolean llc = Math2.isFinite(minLon) || Math2.isFinite(maxLon) ||
                      Math2.isFinite(minLat) || Math2.isFinite(maxLat);
        EDStatic.tally.add("Advanced Search with Lat Lon Constraints (since startup)", "" + llc);
        EDStatic.tally.add("Advanced Search with Lat Lon Constraints (since last daily report)", "" + llc);


        double minTimeD = Calendar2.safeIsoStringToEpochSeconds(request.getParameter("minTime"));
        double maxTimeD = Calendar2.safeIsoStringToEpochSeconds(request.getParameter("maxTime"));
        if (!Double.isNaN(minTimeD) && !Double.isNaN(maxTimeD) && minTimeD > maxTimeD) {
            double td = minTimeD; minTimeD = maxTimeD; maxTimeD = td; }
        String minTime  = Calendar2.safeEpochSecondsToIsoStringTZ(minTimeD, "");
        String maxTime  = Calendar2.safeEpochSecondsToIsoStringTZ(maxTimeD, "");
        boolean tc = Math2.isFinite(minTimeD) || Math2.isFinite(maxTimeD);
        EDStatic.tally.add("Advanced Search with Time Constraints (since startup)", "" + tc);
        EDStatic.tally.add("Advanced Search with Time Constraints (since last daily report)", "" + tc);

        //categories
        String catSAs[][] = new String[nCatAtts][];
        int whichCatSAIndex[] = new int[nCatAtts];
        for (int ca = 0; ca < nCatAtts; ca++) {
            //get user cat params and validate them (so items on form match items used for search)
            StringArray tsa = categoryInfo(catAtts[ca]);
            tsa.add(0, ANY);
            catSAs[ca] = tsa.toArray();    
            String tParam = request.getParameter(catAttsInURLs[ca]);
            whichCatSAIndex[ca] = 
                (tParam == null || tParam.equals(""))? 0 :
                    Math.max(0, String2.indexOf(catSAs[ca], tParam));
            if (whichCatSAIndex[ca] > 0) {
                EDStatic.tally.add("Advanced Search with Category Constraints (since startup)", 
                    catAttsInURLs[ca] + " = " + tParam);
                EDStatic.tally.add("Advanced Search with Category Constraints (since last daily report)", 
                    catAttsInURLs[ca] + " = " + tParam);
            }
        }

        //protocol
        StringBuilder protocolTooltip = new StringBuilder(
            EDStatic.protocolSearch2Html +
            "\n<p><b>griddap</b> - "  + EDStatic.EDDGridDapDescription +
            "\n<p><b>tabledap</b> - " + EDStatic.EDDTableDapDescription +
            "\n<p><b>WMS</b> - "      + EDStatic.wmsDescriptionHtml);
        StringArray protocols = new StringArray();
        protocols.add(ANY);
        protocols.add("griddap");
        protocols.add("tabledap");
        protocols.add("WMS");
        if (EDStatic.wcsActive) {
            protocols.add("WCS");
            protocolTooltip.append("\n<p><b>WCS</b> - " + EDStatic.wcsDescriptionHtml);
        }
        if (EDStatic.sosActive) {
            protocols.add("SOS");
            protocolTooltip.append("\n<p><b>SOS</b> - " + EDStatic.sosDescriptionHtml);
        }
        String tProt = request.getParameter("protocol");
        int whichProtocol = Math.max(0, protocols.indexOf(tProt)); 
        if (whichProtocol > 0) {
            EDStatic.tally.add("Advanced Search with Category Constraints (since startup)", 
                "protocol = " + tProt);
            EDStatic.tally.add("Advanced Search with Category Constraints (since last daily report)", 
                "protocol = " + tProt);
        }


        //get fileTypeName
        fileTypeName = File2.getExtension(endOfRequestUrl); //eg ".html"
        boolean toHtml = fileTypeName.equals(".html");
        if (reallyVerbose) String2.log("Advanced Search   fileTypeName=" + fileTypeName +
            "\n  searchFor=" + searchFor + 
            "\n  whichCatSAString=" + whichCatSAIndex.toString());
        EDStatic.tally.add("Advanced Search, .fileType (since startup)", fileTypeName);
        EDStatic.tally.add("Advanced Search, .fileType (since last daily report)", fileTypeName);

        //*** if .html request, show the form 
        OutputStream out = null;
        Writer writer = null; 
        if (toHtml) { 
            //display start of web page
            out = getHtmlOutputStream(request, response);
            writer = getHtmlWriter(loggedInAs, "Advanced Search", out); 
            try {
                HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
                widgets.htmlTooltips = true;
                widgets.enterTextSubmitsForm = true; 

                //display the advanced search form
                String formName = "f1";
                writer.write(
                    EDStatic.youAreHere(loggedInAs, "Advanced Search " +
                        EDStatic.htmlTooltipImage(loggedInAs, 
                            EDStatic.advancedSearchHtml)) + "\n\n" +
                    EDStatic.advancedSearchDirections + "\n" +
                    widgets.beginForm(formName, "GET",
                        tErddapUrl + "/search/advanced.html", "") + "\n");

                //full text search...
                writer.write(
                    "<p><b>Full Text Search for Datasets</b>\n" + 
                    EDStatic.htmlTooltipImage(loggedInAs, EDStatic.searchHintsHtml(tErddapUrl)) + "\n" +
                    "<br>" +
                    widgets.textField("searchFor", EDStatic.searchTip, 70, 255, searchFor, "") + "\n");

                //categorize      
                //a table with a row for each attribute
                writer.write(
                    "&nbsp;\n" + //necessary for the blank line before the form (not <p>)
                    widgets.beginTable(0, 0, "") +
                    "<tr>\n" +
                    "  <td align=\"left\" colspan=\"2\"><b>" + EDStatic.categoryTitleHtml + "</b>\n" +
                    EDStatic.htmlTooltipImage(loggedInAs, 
                        "Datasets can be categorized in different ways" +
                        "<br>based on the values of various metadata attributes." +
                        "<p>Most datasets have multiple values for some attributes," +
                        "<br>for example, the \"long_name\" associated with each variable." +
                        "<br>To search for more than one value, enter additional values in the Full Text" +
                        "<br>textfield above, e.g., <tt>\"long_name=Sea Surface Temperature\"</tt>.") +
                    "  </td>\n" +
                    "</tr>\n" +
                    "<tr>\n" +
                    "  <td>protocol \n" +
                    EDStatic.htmlTooltipImage(loggedInAs, 
                        String2.replaceAll(
                            String2.noLongLinesAtSpace(protocolTooltip.toString(), 80, ""), "\n", "<br>")) + "\n" +
                    "  </td>\n" +
                    "  <td>&nbsp;=&nbsp;\n" +
                    widgets.select("protocol", "", 1, protocols.toArray(), whichProtocol, "") + " \n" +
                    "  </td>\n" +
                    "</tr>\n");                
                for (int ca = 0; ca < nCatAtts; ca++) {
                    if (catSAs[ca].length == 1)
                        continue;
                    //left column: attribute;   right column: values
                    writer.write(
                        "<tr>\n" +
                        "  <td>" + catAttsInURLs[ca] + "</td>\n" +
                        "  <td>&nbsp;=&nbsp;" + 
                        widgets.select(catAttsInURLs[ca], "", 1, catSAs[ca], whichCatSAIndex[ca], "") +
                        "  </td>\n");
                }
                writer.write("</table>\n\n");

                //bounding box...
                String mapTooltip = 
                    "You can specify longitude and latitude bounds by entering text in the textfields," +
                    "<br>or you can specify a rectangle on the map by clicking on two diagonal corners." +
                    "<br>(Do it again if needed.)" +
                    "<br>Drawing a rectangle on the map automatically puts values in the lat and lon textfields." +
                    "<br>The values in the lat and lon textfields are what really count." +
                    "<br>Drawing a rectangle on the map is just an easy way to specify those values.";
                String lonTooltip = 
                    mapTooltip + 
                    "<p>Some datasets have longitude values within 0 to 360, others use -180 to 180." +
                    "<br>You can specify Min and Max Longitude within 0 to 360 or -180 to 180," +
                    "<br>but ERDDAP will only find datasets that match the values you specify." +
                    "<br>Consider doing two searches, e.g., longitude -30 to 0, and 330 to 360.";
                String timeTooltip = 
                    "Use the format yyyy-MM-dd'T'HH:mm:ssZ, for example, 2009-01-21T23:00:00Z." +
                    "<br>If you specify something, you must include yyyy-MM-dd." +
                    "<br>You can omit (backwards from the end) Z, :ss, :mm, :HH, and T." +
                    "<br>Always use UTC (GMT/Zulu) time.";
                String twoClickMap[] = HtmlWidgets.myTwoClickMap540Big(formName, 
                    widgets.imageDirUrl + "world540Big.png", null); //debugInBrowser

                writer.write(
                    "&nbsp;\n" + //necessary for the blank line before the form (not <p>)
                    widgets.beginTable(0, 0, "") +
                    "<tr>\n" +
                    "  <td align=\"left\" colspan=\"3\"><b>" + 
                        "Search for Datasets that have Data within Longitude, Latitude, and Time Ranges</b>\n" +
                    EDStatic.htmlTooltipImage(loggedInAs, 
                        "Datasets will match these criteria if they have a least some matching data." +
                        "<p>All grid datasets that have longitude, latitude and time variables\n" +
                        "<br>have the data-range information needed to do these tests." +
                        "<br>Unfortunately, some tabular datasets have the variables, but don't have" +
                        "<br>the data-range information. They will be excluded from the search results." +
                        "<p>" + lonTooltip) +
                    "  </td>\n" +
                    "</tr>\n" +

                    //line 1
                    "<tr>\n" +
                    "  <td>Maximum Latitude:&nbsp;</td>\n" +
                    "  <td>" + 
                    "    &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;\n" +
                        widgets.textField("maxLat", "Maximum Latitude (-90 to 90)<p>" + mapTooltip, 8, 8, 
                            (Double.isNaN(maxLat)? "" : "" + maxLat), "") + 
                    "    &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;\n" +
                    "    </td>\n" +
                    "  <td rowspan=\"7\" nowrap>&nbsp;&nbsp;" + twoClickMap[0] + 
                        EDStatic.htmlTooltipImage(loggedInAs, lonTooltip) + 
                        twoClickMap[1] + 
                        "</td>\n" +
                    "</tr>\n" +

                    //line 2
                    "<tr>\n" +
                    "  <td>Min and Max Longitude:&nbsp;</td>\n" +
                    "  <td>" + 
                        widgets.textField("minLon", "Minimum Longitude<p>" + lonTooltip, 8, 8, 
                            (Double.isNaN(minLon)? "" : "" + minLon), "") + "\n" +
                        widgets.textField("maxLon", "Maximum Longitude<p>" + lonTooltip, 8, 8, 
                            (Double.isNaN(maxLon)? "" : "" + maxLon), "") +
                        "</td>\n" +
                    "</tr>\n" +

                    //line 3
                    "<tr>\n" +
                    "  <td>Minimum Latitude:&nbsp;</td>\n" +
                    "  <td>" + 
                    "    &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;\n" +
                        widgets.textField("minLat", "Minimum Latitude (-90 to 90)<p>" + mapTooltip, 8, 8, 
                            (Double.isNaN(minLat)? "" : "" + minLat), "") +
                    "    &nbsp;\n" +
                        widgets.htmlButton("button", "", "", 
                            "Clear all of the Longitude and Latitude textfields and the rectangle on the map.",
                            "Clear",
                            "onClick='f1.minLon.value=\"\"; f1.maxLon.value=\"\"; " +
                                     "f1.minLat.value=\"\"; f1.maxLat.value=\"\"; " +
                                     "((document.all)? document.all.rubberBand : document.getElementById(\"rubberBand\")).style.visibility=\"hidden\";'") +
                    "    </td>\n" +
                    "</tr>\n" +
                     
                    //lines 4, 5   time
                    "<tr>\n" +
                    "  <td>Min Time:&nbsp;</td>\n" +
                    "  <td>" + widgets.textField("minTime", "Minimum Time<p>" + timeTooltip, 27, 27, minTime, "") + "</td>\n" +
                    "</tr>\n" +
                    "<tr>\n" +
                    "  <td>Max Time:&nbsp;</td>\n" +
                    "  <td>" + widgets.textField("maxTime", "Maximum Time<p>" + timeTooltip, 27, 27, maxTime, "") + "</td>\n" +
                    "</tr>\n" +

                    //line 6   blank 
                    "<tr>\n" +
                    "  <td>&nbsp;</td>\n" +
                    "</tr>\n" +

                    //line 7  submit button 
                    "<tr>\n" +
                    "  <td>" + 
                        widgets.htmlButton("submit", null, null, EDStatic.searchClickTip, 
                            "<b>" + EDStatic.searchButton + "</b>", "") +
                    "  </td>\n" +
                    "</tr>\n" +

                    //line 8   blank 
//                    "<tr>\n" +
//                    "  <td>&nbsp;</td>\n" +
//                    "</tr>\n" +
                    "</table>\n\n" +

                    //end form
                    widgets.endForm() + "\n" +
                    twoClickMap[2]);
                writer.flush();

            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
                endHtmlWriter(out, writer, tErddapUrl, false);
                return;
            }
        }

        //*** do the search
        StringArray matchingDatasetIDs = null;

        //test protocol first...
        if (whichProtocol > 0) {
            String protocol = protocols.get(whichProtocol);
            if (protocol.equals("griddap")) {
                matchingDatasetIDs = gridDatasetIDs(false);   //this assumes protocol is handled first 
            } else if (protocol.equals("tabledap")) {
                matchingDatasetIDs = tableDatasetIDs(false);  //this assumes protocol is handled first
            } else {
                matchingDatasetIDs = allDatasetIDs(false);    //this assumes protocol is handled first
                boolean testWMS = protocol.equals("WMS");
                boolean testWCS = protocol.equals("WCS");
                boolean testSOS = protocol.equals("SOS");
                int dsn = matchingDatasetIDs.size();
                BitSet keep = new BitSet(dsn);
                keep.set(0, dsn, true);  //so look for a reason not to keep it
                for (int dsi = 0; dsi < dsn; dsi++) {
                    String tDatasetID = matchingDatasetIDs.get(dsi);
                    EDD edd = (EDD)gridDatasetHashMap.get(tDatasetID);
                    if (edd == null) 
                        edd = (EDD)tableDatasetHashMap.get(tDatasetID);
                    if (edd == null) {keep.clear(dsi); //e.g., just removed
                    } else if (testWMS) {if (edd.accessibleViaWMS().length() > 0) keep.clear(dsi); 
                    } else if (testWCS) {if (edd.accessibleViaWCS().length() > 0) keep.clear(dsi); 
                    } else if (testSOS) {if (edd.accessibleViaSOS().length() > 0) keep.clear(dsi); 
                    }
                }
                matchingDatasetIDs.justKeep(keep);
            }
        }            


        //test category...
        for (int ca = 0; ca < nCatAtts; ca++) {
            if (whichCatSAIndex[ca] > 0) {
                StringArray tMatching = categoryInfo(catAtts[ca], catSAs[ca][whichCatSAIndex[ca]]);
                tMatching.sort(); //must be plain sort()
                if  (matchingDatasetIDs == null)
                     matchingDatasetIDs = tMatching;
                else matchingDatasetIDs.inCommon(tMatching); 
            //String2.log("  after " + catAttsInURLs[ca] + ", nMatching=" + matchingDatasetIDs.size()); 
            }
        }

        //test bounding box...
        boolean testLon  = !Double.isNaN(minLon  ) || !Double.isNaN(maxLon  );
        boolean testLat  = !Double.isNaN(minLat  ) || !Double.isNaN(maxLat  );
        boolean testTime = !Double.isNaN(minTimeD) || !Double.isNaN(maxTimeD);
        if (testLon || testLat || testTime) {
            if (matchingDatasetIDs == null)
                matchingDatasetIDs = allDatasetIDs(false);
            int dsn = matchingDatasetIDs.size();
            BitSet keep = new BitSet(dsn);
            keep.set(0, dsn, true);  //so look for a reason not to keep it
            for (int dsi = 0; dsi < dsn; dsi++) {
                String tDatasetID = matchingDatasetIDs.get(dsi);
                EDDGrid eddg = (EDDGrid)gridDatasetHashMap.get(tDatasetID);
                EDV lonEdv = null, latEdv = null, timeEdv = null;
                if (eddg == null) {
                    EDDTable eddt = (EDDTable)tableDatasetHashMap.get(tDatasetID);
                    if (eddt != null) {
                        if (eddt.lonIndex( ) >= 0) lonEdv  = eddt.dataVariables()[eddt.lonIndex()];
                        if (eddt.latIndex( ) >= 0) latEdv  = eddt.dataVariables()[eddt.latIndex()];
                        if (eddt.timeIndex() >= 0) timeEdv = eddt.dataVariables()[eddt.timeIndex()];
                    }
                } else {
                        if (eddg.lonIndex( ) >= 0) lonEdv  = eddg.axisVariables()[eddg.lonIndex()];
                        if (eddg.latIndex( ) >= 0) latEdv  = eddg.axisVariables()[eddg.latIndex()];
                        if (eddg.timeIndex() >= 0) timeEdv = eddg.axisVariables()[eddg.timeIndex()];
                }

                //testLon
                if (testLon) {
                    if (lonEdv == null) {
                        keep.clear(dsi);
                    } else {
                        if (!Double.isNaN(minLon)) {
                            if (Double.isNaN(lonEdv.destinationMax()) ||
                                minLon > lonEdv.destinationMax()) {
                                keep.clear(dsi);
                            }
                        }
                        if (!Double.isNaN(maxLon)) {
                            if (Double.isNaN(lonEdv.destinationMin()) ||
                                maxLon < lonEdv.destinationMin()) {
                                keep.clear(dsi);
                            }
                        }
                    }
                }

                //testLat
                if (testLat) {
                    if (latEdv == null) {
                        keep.clear(dsi);
                    } else {
                        if (!Double.isNaN(minLat)) {
                            if (Double.isNaN(latEdv.destinationMax()) ||
                                minLat > latEdv.destinationMax()) {
                                keep.clear(dsi);
                            }
                        }
                        if (!Double.isNaN(maxLat)) {
                            if (Double.isNaN(latEdv.destinationMin()) ||
                                maxLat < latEdv.destinationMin()) {
                                keep.clear(dsi);
                            }
                        }
                    }
                }

                //testTime
                if (testTime) {
                    if (timeEdv == null) {
                        keep.clear(dsi);
                    } else {
                        if (!Double.isNaN(minTimeD)) {
                            if (Double.isNaN(timeEdv.destinationMax())) {
                                //test is ambiguous, since destMax=NaN may mean current time
                            } else if (minTimeD > timeEdv.destinationMax()) {
                                keep.clear(dsi);
                            }
                        }
                        if (!Double.isNaN(maxTimeD)) {
                            if (Double.isNaN(timeEdv.destinationMin()) ||
                                maxTimeD < timeEdv.destinationMin()) {
                                keep.clear(dsi);
                            }
                        }
                    }
                }
            }
            matchingDatasetIDs.justKeep(keep);
            //String2.log("  after boundingBox, nMatching=" + matchingDatasetIDs.size()); 
        }
            
        //do text search last, since it is the most time-consuming
        Table resultsTable = null;
        if (searchFor.equals("all")) {
            //no need to do the full text search
            if (matchingDatasetIDs == null)
                matchingDatasetIDs = allDatasetIDs(false);
            //show datasets sorted by title
            boolean sortByTitle = true;
            if (toHtml)
                 resultsTable = makeHtmlDatasetTable( loggedInAs, matchingDatasetIDs, sortByTitle);  
            else resultsTable = makePlainDatasetTable(loggedInAs, matchingDatasetIDs, sortByTitle, fileTypeName);  

        } else if (searchFor.length() > 0) {
            //do the full text search
            if (matchingDatasetIDs == null)
                matchingDatasetIDs = allDatasetIDs(false);
            resultsTable = getSearchTable(loggedInAs, matchingDatasetIDs, 
                searchFor, toHtml, fileTypeName);

        } else {
            //show the selected datasets, if any
            boolean sortByTitle = true;
            if (matchingDatasetIDs != null) {
                if (toHtml)
                     resultsTable = makeHtmlDatasetTable( loggedInAs, matchingDatasetIDs, sortByTitle);  
                else resultsTable = makePlainDatasetTable(loggedInAs, matchingDatasetIDs, sortByTitle, fileTypeName);  
            }
        }
       
        boolean searchPerformed = resultsTable != null;        


        //*** show the .html results
        if (toHtml) { 
            try {
                //display datasets
                writer.write(
                    //"<br>&nbsp;\n" +
                    "<hr>\n" +
                    "<h2>Advanced Search Results</h2>\n");  
                if (searchPerformed) {
                    if (resultsTable.nRows() == 0) {
                         writer.write("<b>" + XML.encodeAsHTML(EDStatic.THERE_IS_NO_DATA) + "</b>\n" +
                             (searchFor.length() > 0? "<br>" + EDStatic.searchSpelling + "\n" : "") +
                             "<br>Try using fewer and/or broader search criteria.\n");
                    } else {

                        if (searchFor.length() > 0 && !searchFor.equals("all"))
                            if (resultsTable.nRows() == 1) 
                                writer.write(
                                "1 dataset matches all of the search criteria.\n");
                            else writer.write(
                                resultsTable.nRows() + " datasets match all of the search criteria. " +
                                EDStatic.searchRelevantAreFirst + "\n");

                        else if (resultsTable.nRows() == 1) 
                            writer.write( 
                                "The 1 dataset which matches all of the search\n" +
                                "criteria is listed below.\n");

                        else writer.write(  //resultsTable > 1 rows
                                "The " + resultsTable.nRows() + " datasets which match all of the search\n" +
                                "criteria are listed in alphabetical order, by Title.\n");
                            //was "Lower rating numbers indicate a better match.\n" +
                            //+ "<br>" + EDStatic.clickAccessHtml + "\n" +
                            //"<br>&nbsp;\n"

                        writer.write("<br>&nbsp;\n"); //necessary for the blank line before the form (not <p>)
                        resultsTable.saveAsHtmlTable(writer, "commonBGColor", null, 1, false, -1, 
                            false, false); //don't encodeAsHTML the cell's contents, !allowWrap
                    }
                } else {
                    writer.write(
                        "To see some results, you must specify at least one search criterion above,\n" +
                        "then click <tt>" + EDStatic.searchButton + "</tt>.\n" +
                        "<br>Or, <a href=\"" + tErddapUrl + "/search/advanced.html?searchFor=all\">" +
                        "view a list of all " + 
                        (gridDatasetHashMap.size() + tableDatasetHashMap.size()) +
                        " datasets</a>.");
                }

            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
            }
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }

        //return non-html file types 
        if (endsWithPlainFileType(endOfRequestUrl, "advanced")) {
            if (searchPerformed) {
                //show the results in other file types
                sendPlainTable(loggedInAs, request, response, resultsTable, 
                    "AdvancedSearch", fileTypeName);
                return;
            } else {
                sendResourceNotFoundError(request, response, //or SC_NO_CONTENT error?
                    "A " + requestUrl + 
                    " request must include at least one search criteria.");
                return;
            }
        }

    }

    /**
     * This gets the HTML for a table with (usually) YouAreHere on the left 
     * and other things on the right.
     */
    public static String getYouAreHereTable(String leftSide, String rightSide) 
        throws Throwable {

        //begin table
        StringBuilder sb = new StringBuilder(
            "<table width=\"100%\" border=\"0\" cellspacing=\"2\" cellpadding=\"0\">\n" +
            "<tr>\n" +
            "<td width=\"90%\">\n");

        //you are here
        sb.append(leftSide);                   
        sb.append(
            "</td>\n" +
            "<td width=\"10%\" nowrap>\n");

        //rightside
        sb.append(rightSide);

        //end table
        sb.append(
            "</td>\n" +
            "</tr>\n" +
            "</table>\n");

        return sb.toString();
    }

    /**
     * This generates a results table in response to a searchFor string.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param tDatasetIDs  The datasets to be considered (usually allDatasetIDs(false)). 
     *    The order (sorted or not) is irrelevant.
     * @param searchFor the Google-like string of search terms.
     * @param toHtml if true, this returns a table with values suited
     *    to display via HTML. If false, the table has plain text values
     * @param fileTypeName the file type name (e.g., .htmlTable) to be used
     *    for the info links.
     * @return a table with the results.
     *    It may have 0 rows.
     */
    public Table getSearchTable(String loggedInAs, StringArray tDatasetIDs,
        String searchFor, boolean toHtml, String fileTypeName) {

        //*** respond to search request
        StringArray searchWords = StringArray.wordsAndQuotedPhrases(searchFor);
        for (int i = 0; i < searchWords.size(); i++)
            searchWords.set(i, searchWords.get(i).toLowerCase());
        
        //gather the matching datasets
        Table table = new Table();
        IntArray rankPa = new IntArray();
        StringArray idPa = new StringArray();
        int rankCol = table.addColumn("rank", rankPa); 
        int idCol   = table.addColumn("id", idPa);

        //NO LONGER USED    
        //make the list of possible datasetIDs
        //StringArray tDatasetIDs
        //int gpo = searchWords.indexOf("type=grid");  //see info in getSearchFormHtml
        //int tpo = searchWords.indexOf("type=table");
        //if      (gpo >= 0 && tpo <  0) {tDatasetIDs = gridDatasetIDs(false);  searchWords.remove(gpo); if (searchWords.size() == 0) searchWords.add("");}
        //else if (gpo < 0  && tpo >= 0) {tDatasetIDs = tableDatasetIDs(false); searchWords.remove(tpo); if (searchWords.size() == 0) searchWords.add("");}
        //else if (gpo < 0  && tpo <  0) {tDatasetIDs = allDatasetIDs(false); }
        //else {tDatasetIDs = new StringArray(); searchWords.clear();} //no datasets are grid and table

        //do the search; populate the results table 
        String roles[] = EDStatic.getRoles(loggedInAs);
        int nDatasetsSearched = 0;
        long tTime = System.currentTimeMillis();
        if (searchWords.size() > 0) {
            //prepare the byte[]s
            byte searchWordsB[][] = new byte[searchWords.size()][];
            int  jumpB[][]        = new int[ searchWords.size()][];
            for (int w = 0; w < searchWords.size(); w++) {
                searchWordsB[w] = String2.getUTF8Bytes(searchWords.get(w));
                jumpB[w] = String2.makeJumpTable(searchWordsB[w]);
            }

            //do the searches
            for (int i = 0; i < tDatasetIDs.size(); i++) {
                String tId = tDatasetIDs.get(i);
                EDD edd = (EDD)gridDatasetHashMap.get(tId);
                if (edd == null)
                    edd = (EDD)tableDatasetHashMap.get(tId);
                if (edd == null)  //just deleted?
                    continue;
                if (!EDStatic.listPrivateDatasets && !edd.isAccessibleTo(roles))
                    continue;
                nDatasetsSearched++;
                int rank = edd.searchRank(searchWordsB, jumpB);           
                if (rank < Integer.MAX_VALUE) {
                    rankPa.add(rank);
                    idPa.add(tId);
                }
            }
        }
        if (verbose) {
            tTime = System.currentTimeMillis() - tTime;
            String2.log("Erddap.search " +
                //"searchFor=" + searchFor + "\n" +
                //"searchWords=" + searchWords.toString() + "\n" +
                "nDatasetsSearched=" + nDatasetsSearched + 
                " nWords=" + searchWords.size() + " totalTime=" + tTime + "ms"); 
                //" avgTime=" + (tTime / Math.max(1, nDatasetsSearched*searchWords.size())));
        }
        table.sort(new int[]{rankCol, idCol}, new boolean[]{true,true});

        return toHtml? 
            makeHtmlDatasetTable(loggedInAs, idPa, false) :   //roles check is redundant for some cases, but not all
            makePlainDatasetTable(loggedInAs, idPa, false, fileTypeName);
    }

    /**
     * Process a categorize request:    erddap/categorize/{attribute}/{categoryName}/index.html
     * e.g., erddap/categorize/ioos_category/temperature/index.html
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (here always "categorize") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doCategorize(HttpServletRequest request, HttpServletResponse response,
        String loggedInAs, String protocol, int datasetIDStartsAt, String userQuery) throws Throwable {
        
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String fileTypeName = "";
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);

        //parse endOfRequestUrl into parts
        String parts[] = String2.split(endOfRequestUrl, '/');
        String attributeInURL = parts.length < 1? "" : parts[0];
        int whichAttribute = String2.indexOf(EDStatic.categoryAttributesInURLs, attributeInURL);
        if (reallyVerbose) String2.log("  attributeInURL=" + attributeInURL + " which=" + whichAttribute);
        String attribute = whichAttribute < 0? "" : EDStatic.categoryAttributes[whichAttribute];

        String categoryName = parts.length < 2? "" : parts[1];
        if (reallyVerbose) String2.log("  categoryName=" + categoryName);

        //if {attribute}/index.html and there is only 1 categoryName
        //  redirect to {attribute}/{categoryName}/index.html
        if (whichAttribute >= 0 && parts.length == 2 && categoryName.equals("index.html")) {
            String values[] = categoryInfo(attribute).toArray();
            if (values.length == 1) {
                response.sendRedirect(tErddapUrl + "/" + protocol + "/" + 
                    attributeInURL + "/" + values[0] + "/index.html");
                return;
            }
        }


        //generate the youAreHereTable
        String advancedQuery = "";
        if (parts.length == 3 && parts[2].equals("index.html"))
            advancedQuery = parts[0] + "=" + SSR.minimalPercentEncode(parts[1]);
        String refine = "&nbsp;<br>&nbsp;";
        if (advancedQuery.length() > 0)
            refine = 
                "&nbsp;\n" +
                ////Or, View All Datasets
                //"<br>Or, <a href=\"" + tErddapUrl + "/info/index.html\">" +
                //EDStatic.viewAllDatasetsHtml + "</a>\n" +
                ////Or, search text
                //"<p>" + getSearchFormHtml(loggedInAs, "Or, ", ":\n<br>", "") +
                //Use <p> below if other options above are enabled.
                "<br>Or, Refine this Search with " + getAdvancedSearchLink(loggedInAs, advancedQuery) +
                "&nbsp;&nbsp;&nbsp;";

        String youAreHereTable = 
            getYouAreHereTable(
                EDStatic.youAreHere(loggedInAs, EDStatic.categoryTitleHtml), //protocol),
                refine) +
            "\n" + HtmlWidgets.ifJavaScriptDisabled;

        //*** attribute string should be e.g., ioos_category
        fileTypeName = File2.getExtension(endOfRequestUrl);
        if (whichAttribute < 0) {
            //*** deal with invalid attribute string

            //redirect to index.html
            if (attributeInURL.equals("") ||
                attributeInURL.equals("index.htm")) {
                response.sendRedirect(tErddapUrl + "/" + protocol + "/index.html");
                return;
            }   
            
            //return table of categoryAttributes
            if (String2.indexOf(plainFileTypes, fileTypeName) >= 0) {
                //plainFileType
                if (attributeInURL.equals("index" + fileTypeName)) {
                    //respond to categorize/index.xxx
                    //display list of categoryAttributes in plainFileType file
                    Table table = categorizeOptionsTable(tErddapUrl, fileTypeName);
                    sendPlainTable(loggedInAs, request, response, table, protocol, fileTypeName);
                } else {
                    sendResourceNotFoundError(request, response, "");
                    return;
                }
            } else { 
                //respond to categorize/index.html or errors: unknown attribute, unknown fileTypeName 
                OutputStream out = getHtmlOutputStream(request, response);
                Writer writer = getHtmlWriter(loggedInAs, "Categorize", out); 
                try {
                    //you are here  Categorize    
                    writer.write(youAreHereTable);

                    if (!attributeInURL.equals("index.html")) 
                        writeErrorHtml(writer, request, "categoryAttribute=\"" + attributeInURL + "\" is not an option.");
                    writeCategorizeOptionsHtml1(loggedInAs, writer, null, false);
                } catch (Throwable t) {
                    writer.write(EDStatic.htmlForException(t));
                }
                endHtmlWriter(out, writer, tErddapUrl, false);
            }
            return;
        }   
        //attribute is valid
        if (reallyVerbose) String2.log("  attribute=" + attribute + " is valid.");


        //*** categoryName string should be e.g., Location
        //*** deal with index.xxx and invalid categoryName
        StringArray catDats = categoryInfo(attribute, categoryName); 
        if (catDats.size() == 0) {

            //redirect to index.html
            if (categoryName.equals("") ||
                categoryName.equals("index.htm")) {
                response.sendRedirect(tErddapUrl + "/" + protocol + "/" + 
                    attributeInURL + "/index.html");
                return;
            }   
            
            //redirect to lowercase?
            if (parts.length >= 2) {
                catDats = categoryInfo(attribute, categoryName.toLowerCase());
                if (catDats.size() > 0) {
                    parts[1] = parts[1].toLowerCase();
                    response.sendRedirect(tErddapUrl + "/" + protocol + "/" +
                        String2.toSVString(parts, "/", false));
                    return;
                }   
            }

            //return table of categoryNames
            if (String2.indexOf(plainFileTypes, fileTypeName) >= 0) {
                //plainFileType
                if (categoryName.equals("index" + fileTypeName)) {
                    //respond to categorize/attribute/index.xxx
                    //display list of categoryNames in plainFileType file
                    sendCategoryPftOptionsTable(request, response, loggedInAs, 
                        attribute, attributeInURL, fileTypeName);
                } else {
                    sendResourceNotFoundError(request, response, "");
                    return;
                }
            } else { 
                //respond to categorize/index.html or errors: unknown attribute, unknown fileTypeName 
                OutputStream out = getHtmlOutputStream(request, response);
                Writer writer = getHtmlWriter(loggedInAs, "Categorize", out); 
                try {
                    writer.write(youAreHereTable);
                    if (!categoryName.equals("index.html")) {
                        writeErrorHtml(writer, request, 
                            "categoryName=\"" + categoryName + 
                            "\" is not an option when categoryAttribute=\"" + 
                            attributeInURL + "\".");
                        writer.write("<hr>\n");
                    }
                    writer.write(
                        "<table border=\"0\" cellspacing=\"0\" cellpadding=\"2\">\n" +
                        "<tr>\n" +
                        "<td valign=\"top\">\n");
                    writeCategorizeOptionsHtml1(loggedInAs, writer, attributeInURL, false);
                    writer.write(
                        "</td>\n" +
                        "<td valign=\"top\">\n");
                    writeCategoryOptionsHtml2(loggedInAs, writer, 
                        attribute, attributeInURL, categoryName);
                    writer.write(
                        "</td>\n" +
                        "</tr>\n" +
                        "</table>\n");
                } catch (Throwable t) {
                    writer.write(EDStatic.htmlForException(t));
                }
                endHtmlWriter(out, writer, tErddapUrl, false);
            }
            return;
        }           
        //categoryName is valid
        if (reallyVerbose) String2.log("  categoryName=" + categoryName + " is valid.");

        //*** attribute (e.g., ioos_category) and categoryName (e.g., Location) are valid
        //endOfRequestUrl3 should be index.xxx or {categoryName}.xxx
        String part2 = parts.length < 3? "" : parts[2];

        //redirect categorize/{attribute}/{categoryName}/index.htm request index.html
        if (part2.equals("") ||
            part2.equals("index.htm")) {
            response.sendRedirect(tErddapUrl + "/" + protocol + "/" + 
                attributeInURL + "/" + categoryName + "/index.html");
            return;
        }   

        //*** respond to categorize/{attributeInURL}/{categoryName}/index.fileTypeName request
        EDStatic.tally.add("Categorize Attribute (since startup)", attributeInURL);
        EDStatic.tally.add("Categorize Attribute (since last daily report)", attributeInURL);
        EDStatic.tally.add("Categorize Attribute = Value (since startup)", attributeInURL + " = " + categoryName);
        EDStatic.tally.add("Categorize Attribute = Value (since last daily report)", attributeInURL + " = " + categoryName);
        EDStatic.tally.add("Categorize File Type (since startup)", fileTypeName);
        EDStatic.tally.add("Categorize File Type (since last daily report)", fileTypeName);
        if (endsWithPlainFileType(part2, "index")) {
            //show the results as plain file type
            Table table = makePlainDatasetTable(loggedInAs, catDats, true, fileTypeName);
            sendPlainTable(loggedInAs, request, response, table, 
                attributeInURL + "_" + categoryName, fileTypeName);
            return;
        }

        //respond to categorize/{attributeInURL}/{categoryName}/index.html request
        if (part2.equals("index.html")) {
            //make a table of the datasets
            Table table = makeHtmlDatasetTable(loggedInAs, catDats, true);

            //display start of web page
            OutputStream out = getHtmlOutputStream(request, response);
            Writer writer = getHtmlWriter(loggedInAs, "Categorize", out); 
            try {
                writer.write(youAreHereTable);

                //write categorizeOptions
                writer.write(
                    "<table border=\"0\" cellspacing=\"0\" cellpadding=\"2\">\n" +
                    "<tr>\n" +
                    "<td valign=\"top\">\n");
                writeCategorizeOptionsHtml1(loggedInAs, writer, attributeInURL, false);
                writer.write(
                    "</td>\n" +
                    "<td valign=\"top\">\n");

                //write categoryOptions
                writeCategoryOptionsHtml2(loggedInAs, writer, 
                    attribute, attributeInURL, categoryName);
                writer.write(
                    "</td>\n" +
                    "</tr>\n" +
                    "</table>\n");

                //display datasets
                writer.write("<h3>3) " + EDStatic.resultsOfSearchFor + " " + attributeInURL + 
                    " = <font class=\"highlightColor\">" + categoryName + "</font> &nbsp; " +
                    //EDStatic.htmlTooltipImage(loggedInAs, 
                        //table.nRows() + " " + EDStatic.nDatasetsListed + "\n" +                    
                        //"<br>" + EDStatic.clickAccessHtml + "\n") +
                    "</h3>\n" +
                    "<b>Pick a dataset:</b>\n&nbsp;&nbsp;" + 
                    "(Or, refine this search with " + getAdvancedSearchLink(loggedInAs, advancedQuery) + ")\n" +
                    "<br>&nbsp;\n"); //necessary for the blank line before the table (not <p>)
                table.saveAsHtmlTable(writer, "commonBGColor", null, 1, false, -1, false, false);        
                writer.write("<p>" + table.nRows() + " " + EDStatic.nDatasetsListed + "\n");
            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
            }

            //end of document
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }

        sendResourceNotFoundError(request, response, "");
    }

    /**
     * Process an info request: erddap/info/[{datasetID}/index.xxx]
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "info") in the requestUrl
     * @throws Throwable if trouble
     */
    public void doInfo(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String protocol, int datasetIDStartsAt) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);
        String fileTypeName = File2.getExtension(endOfRequestUrl);

        String parts[] = String2.split(endOfRequestUrl, '/');
        int nParts = parts.length;
        if (nParts == 0 || !parts[nParts - 1].startsWith("index.")) {
            StringArray sa = new StringArray(parts);
            sa.add("index.html");
            parts = sa.toArray();
            nParts = parts.length;
            //now last part is "index...."
        }
        fileTypeName = File2.getExtension(endOfRequestUrl);        
        boolean endsWithPlainFileType = endsWithPlainFileType(parts[nParts - 1], "index");
        if (!endsWithPlainFileType && !fileTypeName.equals(".html")) {
            sendResourceNotFoundError(request, response, 
                "Unsupported fileExtension=" + fileTypeName);
            return;
        }
        EDStatic.tally.add("Info File Type (since startup)", fileTypeName);
        EDStatic.tally.add("Info File Type (since last daily report)", fileTypeName);
        if (nParts < 2) {
            //view all datasets
            StringArray tIDs = allDatasetIDs(true);
            EDStatic.tally.add("Info (since startup)", "View All Datasets");
            EDStatic.tally.add("Info (since last daily report)", "View All Datasets");

            if (fileTypeName.equals(".html")) {
                //make the table with the dataset list
                Table table = makeHtmlDatasetTable(loggedInAs, tIDs, true);

                //display start of web page
                OutputStream out = getHtmlOutputStream(request, response);
                Writer writer = getHtmlWriter(loggedInAs, "List of All Datasets", out); 
                try {
                    //you are here  View All Datasets
                    String secondLine = table.nRows() == 0?
                        "&nbsp;<br><b>" + EDStatic.THERE_IS_NO_DATA + "</b>\n" :
                        "<h2>&nbsp;<br>Pick a Dataset</h2>\n";

                    writer.write(getYouAreHereTable(
                        EDStatic.youAreHere(loggedInAs, "List of All Datasets") +
                        secondLine, 

                        //Or, search text
                        "&nbsp;\n" +
                        "<br>" + getSearchFormHtml(loggedInAs, "Or, ", ":\n<br>", "") +
                        //Or, by category
                        "<p>" + getCategoryLinksHtml(tErddapUrl) +
                        //Or,
                        "<p>Or, Search for Datasets with " + getAdvancedSearchLink(loggedInAs, "")));
                   

                    if (table.nRows() > 0) {

                        //show the table of all datasets 
                        table.saveAsHtmlTable(writer, "commonBGColor", null, 
                            1, false, -1, false, false);        
                        writer.write(
                            "<p>" + table.nRows() + " " + EDStatic.nDatasetsListed + "\n");
                    }
                } catch (Throwable t) {
                    writer.write(EDStatic.htmlForException(t));
                }

                //end of document
                endHtmlWriter(out, writer, tErddapUrl, false);
            } else {
                Table table = makePlainDatasetTable(loggedInAs, tIDs, true, fileTypeName);
                sendPlainTable(loggedInAs, request, response, table, protocol, fileTypeName);
            }
            return;
        }
        if (nParts > 2) {
            sendResourceNotFoundError(request, response, 
                "erddap/info requests must be in the form erddap/info/<datasetID>/index<fileType> .");
            return;
        }
        String tID = parts[0];
        EDD edd = (EDD)gridDatasetHashMap.get(tID);
        if (edd == null)
            edd = (EDD)tableDatasetHashMap.get(tID);
        if (edd == null) { 
            sendResourceNotFoundError(request, response,
                EDStatic.noDatasetWith + " datasetID=" + tID + ".");
            return;
        }
        if (!edd.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { //listPrivateDatasets doesn't apply
            EDStatic.redirectToLogin(loggedInAs, response, tID);
            return;
        }

        //request is valid -- make the table
        EDStatic.tally.add("Info (since startup)", tID);
        EDStatic.tally.add("Info (since last daily report)", tID);
        Table table = new Table();
        StringArray rowTypeSA = new StringArray();
        StringArray variableNameSA = new StringArray();
        StringArray attributeNameSA = new StringArray();
        StringArray javaTypeSA = new StringArray();
        StringArray valueSA = new StringArray();
        table.addColumn("Row Type", rowTypeSA);
        table.addColumn("Variable Name", variableNameSA);
        table.addColumn("Attribute Name", attributeNameSA);
        table.addColumn("Data Type", javaTypeSA);
        table.addColumn("Value", valueSA);

        //global attribute rows
        Attributes atts = edd.combinedGlobalAttributes();
        String names[] = atts.getNames();
        int nAtts = names.length;
        for (int i = 0; i < nAtts; i++) {
            rowTypeSA.add("attribute");
            variableNameSA.add("NC_GLOBAL");
            attributeNameSA.add(names[i]);
            PrimitiveArray value = atts.get(names[i]);
            javaTypeSA.add(value.elementClassString());
            valueSA.add(Attributes.valueToNcString(value));
        }

        //dimensions
        String axisNamesCsv = "";
        if (edd instanceof EDDGrid) {
            EDDGrid eddGrid = (EDDGrid)edd;
            int nDims = eddGrid.axisVariables().length;
            axisNamesCsv = String2.toCSVString(eddGrid.axisVariableDestinationNames());
            for (int dim = 0; dim < nDims; dim++) {
                //dimension row
                EDVGridAxis edv = eddGrid.axisVariables()[dim];
                rowTypeSA.add("dimension");
                variableNameSA.add(edv.destinationName());
                attributeNameSA.add("");
                javaTypeSA.add(edv.destinationDataType());
                int tSize = edv.sourceValues().size();
                double avgSp = edv.averageSpacing(); //may be negative
                if (tSize == 1) {
                    double dValue = edv.firstDestinationValue();
                    valueSA.add(
                        "nValues=1, onlyValue=" + 
                        (Double.isNaN(dValue)? "NaN" : edv.destinationToString(dValue))); //want "NaN", not ""
                } else {
                    valueSA.add(
                        "nValues=" + tSize + 
                        ", evenlySpaced=" + (edv.isEvenlySpaced()? "true" : "false") +
                        ", averageSpacing=" + 
                        (edv instanceof EDVTimeGridAxis? 
                            Calendar2.elapsedTimeString(Math.rint(avgSp) * 1000) : 
                            avgSp)
                        );
                }

                //attribute rows
                atts = edv.combinedAttributes();
                names = atts.getNames();
                nAtts = names.length;
                for (int i = 0; i < nAtts; i++) {
                    rowTypeSA.add("attribute");
                    variableNameSA.add(edv.destinationName());
                    attributeNameSA.add(names[i]);
                    PrimitiveArray value = atts.get(names[i]);
                    javaTypeSA.add(value.elementClassString());
                    valueSA.add(Attributes.valueToNcString(value));
                }
            }
        }

        //data variables
        int nVars = edd.dataVariables().length;
        for (int var = 0; var < nVars; var++) {
            //data variable row
            EDV edv = edd.dataVariables()[var];
            rowTypeSA.add("variable");
            variableNameSA.add(edv.destinationName());
            attributeNameSA.add("");
            javaTypeSA.add(edv.destinationDataType());
            valueSA.add(axisNamesCsv);

            //attribute rows
            atts = edv.combinedAttributes();
            names = atts.getNames();
            nAtts = names.length;
            for (int i = 0; i < nAtts; i++) {
                rowTypeSA.add("attribute");
                variableNameSA.add(edv.destinationName());
                attributeNameSA.add(names[i]);
                PrimitiveArray value = atts.get(names[i]);
                javaTypeSA.add(value.elementClassString());
                valueSA.add(Attributes.valueToNcString(value));
            }
        }

        //write the file
        if (endsWithPlainFileType) {
            sendPlainTable(loggedInAs, request, response, table, parts[0] + "_info", fileTypeName);
            return;
        }

        //respond to index.html request
        if (parts[1].equals("index.html")) {
            //display start of web page
            OutputStream out = getHtmlOutputStream(request, response);
            Writer writer = getHtmlWriter(loggedInAs, "Information about " + 
                edd.title() + ", from " + edd.institution(), out); 
            try {
                writer.write(EDStatic.youAreHere(loggedInAs, protocol, parts[0]));

                //display a table with the one dataset
                //writer.write(EDStatic.clickAccessHtml + "\n" +
                //    "<br>&nbsp;\n");
                StringArray sa = new StringArray();
                sa.add(parts[0]);
                Table dsTable = makeHtmlDatasetTable(loggedInAs, sa, true);
                dsTable.saveAsHtmlTable(writer, "commonBGColor", null, 1, false, -1, false, false);        

                //html format the valueSA values
                for (int i = 0; i < valueSA.size(); i++) 
                    valueSA.set(i, XML.encodeAsPreHTML(valueSA.get(i), 100000));  //???

                //display the info table
                writer.write("<h2>" + EDStatic.infoTableTitleHtml + "</h2>");

                //******** custom table writer (to change color on "variable" rows)
                writer.write(
                    "<table class=\"erd commonBGColor\" cellspacing=\"0\">\n"); 

                //write the column names   
                writer.write("<tr>\n");
                int nColumns = table.nColumns();
                for (int col = 0; col < nColumns; col++) 
                    writer.write("<th>" + table.getColumnName(col) + "</th>\n");
                writer.write("</tr>\n");

                //write the data
                int nRows = table.nRows();
                for (int row = 0; row < nRows; row++) {
                    String s = table.getStringData(0, row);
                    if (s.equals("variable") || s.equals("dimension"))
                         writer.write("<tr class=\"highlightBGColor\">\n"); 
                    else writer.write("<tr>\n"); 
                    for (int col = 0; col < nColumns; col++) {
                        writer.write("<td>"); 
                        s = table.getStringData(col, row);
                        writer.write(s.length() == 0? "&nbsp;" : s); 
                        writer.write("</td>\n");
                    }
                    writer.write("</tr>\n");
                }

                //close the table
                writer.write("</table>\n");
            } catch (Throwable t) {
                writer.write(EDStatic.htmlForException(t));
            }

            //end of document
            endHtmlWriter(out, writer, tErddapUrl, false);
            return;
        }

        sendResourceNotFoundError(request, response, "");

    }

    /**
     * Process erddap/subscriptions/index.html
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param ipAddress the requestor's ipAddress
     * @param endOfRequest e.g., subscriptions/add.html
     * @param protocol is always subscriptions
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "subscriptions") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doSubscriptions(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String ipAddress,
        String endOfRequest, String protocol, int datasetIDStartsAt, String userQuery) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);

        if (endOfRequest.equals("subscriptions") ||
            endOfRequest.equals("subscriptions/")) {
            response.sendRedirect(tErddapUrl + "/" + Subscriptions.INDEX_HTML);
            return;
        }

        EDStatic.tally.add("Subscriptions (since startup)", endOfRequest);
        EDStatic.tally.add("Subscriptions (since last daily report)", endOfRequest);

        if (endOfRequest.equals(Subscriptions.INDEX_HTML)) {
            //fall through
        } else if (!EDStatic.subscriptionSystemActive) {
            sendResourceNotFoundError(request, response, "");
            return;
        } else if (endOfRequest.equals(Subscriptions.ADD_HTML)) {
            doAddSubscription(request, response, loggedInAs, ipAddress, protocol, datasetIDStartsAt, userQuery);
            return;
        } else if (endOfRequest.equals(Subscriptions.LIST_HTML)) {
            doListSubscriptions(request, response, loggedInAs, ipAddress, protocol, datasetIDStartsAt, userQuery);
            return;
        } else if (endOfRequest.equals(Subscriptions.REMOVE_HTML)) {
            doRemoveSubscription(request, response, loggedInAs, protocol, datasetIDStartsAt, userQuery);
            return;
        } else if (endOfRequest.equals(Subscriptions.VALIDATE_HTML)) {
            doValidateSubscription(request, response, loggedInAs, protocol, datasetIDStartsAt, userQuery);
            return;
        } else {
            sendResourceNotFoundError(request, response, "");
            return;
        }

        //display start of web page
        if (reallyVerbose) String2.log("doSubscriptions");
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Subscriptions", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, protocol) +
                EDStatic.subscriptionHtml(tErddapUrl) + "\n");
            if (EDStatic.subscriptionSystemActive) 
                writer.write(
                "<p><b>Options:</b>\n" +
                "<ul>\n" +
                "<li> <a href=\"" + tErddapUrl + "/" + Subscriptions.ADD_HTML      + "\">Add a new subscription</a>\n" +
                "<li> <a href=\"" + tErddapUrl + "/" + Subscriptions.VALIDATE_HTML + "\">Validate a subscription</a>\n" +
                "<li> <a href=\"" + tErddapUrl + "/" + Subscriptions.LIST_HTML     + "\">List your subscriptions</a>\n" +
                "<li> <a href=\"" + tErddapUrl + "/" + Subscriptions.REMOVE_HTML   + "\">Remove a subscription</a>\n" +
                "</ul>\n");
            else writer.write(EDStatic.subscriptionsNotAvailable(tErddapUrl) + "\n");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }


    /** 
     * This html is used at the bottom of many doXxxSubscription web pages. 
     *
     * @param tErddapUrl  from EDStatic.erddapUrl(loggedInAs)  (erddapUrl, or erddapHttpsUrl if user is logged in)
     * @param tEmail  the user's email address (or "")
     */
    private String requestSubscriptionListHtml(String tErddapUrl, String tEmail) {
        return 
            "<br>&nbsp;\n" +
            "<p><b>Or, you can request an email with a\n" +
            "<a href=\"" + tErddapUrl + "/" + Subscriptions.LIST_HTML + 
            (tEmail.length() > 0? "?email=" + tEmail : "") +
            "\">list of your valid and pending subscriptions</a>.</b>\n";
    }

           
    /**
     * Process erddap/subscriptions/add.html.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param ipAddress the requestor's ip address
     * @param protocol is always subscriptions
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "info") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doAddSubscription(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String ipAddress, String protocol, int datasetIDStartsAt, 
        String userQuery) throws Throwable {

        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);

        //parse the userQuery
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, true); //true=lowercase keys
        String tDatasetID = queryMap.get("datasetid"); 
        String tEmail     = queryMap.get("email");
        String tAction    = queryMap.get("action");
        if (tDatasetID == null) tDatasetID = "";
        if (tEmail     == null) tEmail     = "";
        if (tAction    == null) tAction    = "";
        boolean tEmailIfAlreadyValid = String2.parseBoolean(queryMap.get("emailifalreadyvalid")); //default=true 
        boolean tShowErrors          = String2.parseBoolean(queryMap.get("showerrors"));          //default=true; 

        //validate params
        String trouble = "";
        if      (tDatasetID.length() == 0)                                
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionIDUnspecified + "</font>\n";
        else if (tDatasetID.length() > Subscriptions.DATASETID_LENGTH)    
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionIDTooLong + "</font>\n";
        else if (!String2.isFileNameSafe(tDatasetID))                     
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionIDInvalid + "</font>\n";
        else {
            EDD edd = (EDD)gridDatasetHashMap.get(tDatasetID);
            if (edd == null) 
                edd = (EDD)tableDatasetHashMap.get(tDatasetID);
            if (edd == null)                                              
                trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionIDInvalid + "</font>\n";
            else if (!edd.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { //listPrivateDatasets doesn't apply
                EDStatic.redirectToLogin(loggedInAs, response, tDatasetID);
                return;
            }
        }
        if      (tEmail.length() == 0)                                    
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionEmailUnspecified + "</font>\n";
        else if (tEmail.length() > Subscriptions.EMAIL_LENGTH)            
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionEmailTooLong + "</font>\n";
        else if (!String2.isEmailAddress(tEmail))                         
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionEmailInvalid + "</font>\n";
        if      (tAction.length() > Subscriptions.ACTION_LENGTH)          
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionUrlTooLong + "</font>\n";
        else if (!tAction.equals("") && 
            !(tAction.length() > 10 && tAction.startsWith("http://")))    
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionUrlInvalid + "</font>\n";

        //display start of web page
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true; 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Add a Subscription", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, protocol, "add") +
                EDStatic.subscriptionHtml(tErddapUrl) + "\n" +
                EDStatic.subscription2Html(tErddapUrl) + "\n");

            if (tDatasetID.length() > 0 || tEmail.length() > 0 || tAction.length() > 0) {
                if (trouble.length() > 0) {
                    if (tShowErrors) 
                        writer.write("<p><font class=\"warningColor\">" +
                        EDStatic.subscriptionAddError + "</font>\n" +
                        "<ul>\n" +
                        trouble + "\n" +
                        "</ul>\n");
                } else {
                    //try to add 
                    try {
                        int row = EDStatic.subscriptions.add(tDatasetID, tEmail, tAction);
                        if (tEmailIfAlreadyValid || 
                            EDStatic.subscriptions.readStatus(row) == Subscriptions.STATUS_PENDING) {
                            String invitation = EDStatic.subscriptions.getInvitation(ipAddress, row);
                            String tError = EDStatic.email(tEmail, "Subscription Invitation", invitation);
                            if (tError.length() > 0)
                                throw new SimpleException(tError);

                            //tally
                            EDStatic.tally.add("Subscriptions (since startup)", "Add successful");
                            EDStatic.tally.add("Subscriptions (since last daily report)", "Add successful");
                        }
                        writer.write(EDStatic.subscriptionAddSuccess + "\n");
                    } catch (Throwable t) {
                        writer.write("<p><font class=\"warningColor\">" +
                            EDStatic.subscriptionAddError + "\n<br>" + 
                            XML.encodeAsHTML(MustBe.getShortErrorMessage(t)) + "</font>\n");
                        String2.log("Subscription Add Exception:\n" + MustBe.throwableToString(t)); //log stack trace, too

                        //tally
                        EDStatic.tally.add("Subscriptions (since startup)", "Add unsuccessful");
                        EDStatic.tally.add("Subscriptions (since last daily report)", "Add unsuccessful");
                    }
                }
            }

            //show the form
            String urlTT = EDStatic.subscriptionUrlHtml;
            writer.write(
                widgets.beginForm("addSub", "GET", tErddapUrl + "/" + Subscriptions.ADD_HTML, "") +
                EDStatic.subscriptionAddHtml(tErddapUrl) + "\n" +
                widgets.beginTable(0, 0, "") +
                "<tr>\n" +
                "  <td>The datasetID:&nbsp;</td>\n" +
                "  <td>" + widgets.textField("datasetID", 
                    "For example, " + EDStatic.EDDGridIdExample,
                    40, Subscriptions.DATASETID_LENGTH, tDatasetID, 
                    "") + "</td>\n" +
                "</tr>\n" +
                "<tr>\n" +
                "  <td>Your email address:&nbsp;</td>\n" +
                "  <td>" + widgets.textField("email", "", 
                    60, Subscriptions.EMAIL_LENGTH, tEmail, 
                    "") + "</td>\n" +
                "</tr>\n" +
                "<tr>\n" +
                "  <td>The URL/action:&nbsp;</td>\n" +
                "  <td>" + widgets.textField("action", urlTT,
                    80, Subscriptions.ACTION_LENGTH, tAction, "") + "\n" +
                "    " + EDStatic.htmlTooltipImage(loggedInAs, urlTT) +
                "  </td>\n" +
                "</tr>\n" +
                "<tr>\n" +
                "  <td colspan=\"2\">" + widgets.button("submit", null, 
                    EDStatic.clickToSubmit, "Submit", "") + "\n" +
                "    <br>" + EDStatic.subscriptionAdd2 + "\n" +
                "  </td>\n" +
                "</tr>\n" +
                widgets.endTable() +  
                widgets.endForm() +
                EDStatic.subscriptionAbuse + "\n");

            //link to list of subscriptions
            writer.write(requestSubscriptionListHtml(tErddapUrl, tEmail));
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * Process erddap/subscriptions/list.html
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param ipAddress the requestor's ip address
     * @param protocol is always subscriptions
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "info") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doListSubscriptions(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String ipAddress, String protocol, int datasetIDStartsAt, String userQuery) 
        throws Throwable {

        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, true); //true=names toLowerCase
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);

        //process the query
        String tEmail = queryMap.get("email");
        if (tEmail == null) tEmail = "";
        String trouble = "";
        if      (tEmail.length() == 0)                                  
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionEmailUnspecified + "</font>\n";
        else if (tEmail.length() > Subscriptions.EMAIL_LENGTH)          
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionEmailTooLong + "</font>\n";
        else if (!String2.isEmailAddress(tEmail))                       
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionEmailInvalid + "</font>\n";

        //display start of web page
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true; 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "List Subscriptions", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, protocol, "list") +
                EDStatic.subscriptionHtml(tErddapUrl) + "\n");

            if (tEmail.length() > 0) {
                if (trouble.length() > 0) {
                    writer.write("<p><font class=\"warningColor\">" +
                        EDStatic.subscriptionListError + "</font>\n" + 
                        "<ul>\n" +
                        trouble + "\n" +
                        "</ul>\n");
                } else {
                    //try to list the subscriptions
                    try {
                        String tList = EDStatic.subscriptions.listSubscriptions(ipAddress, tEmail);
                        String tError = EDStatic.email(tEmail, "Subscriptions List", tList);
                        if (tError.length() > 0)
                            throw new SimpleException(tError);

                        writer.write(EDStatic.subscriptionListSuccess + "\n");
                        //end of document
                        endHtmlWriter(out, writer, tErddapUrl, false);

                        //tally
                        EDStatic.tally.add("Subscriptions (since startup)", "List successful");
                        EDStatic.tally.add("Subscriptions (since last daily report)", "List successful");
                        return;
                    } catch (Throwable t) {
                        writer.write("<p><font class=\"warningColor\">" +
                            EDStatic.subscriptionListError + "\n" + 
                            "<br>" + XML.encodeAsHTML(MustBe.getShortErrorMessage(t)) + "</font>\n");
                        String2.log("Subscription list Exception:\n" + MustBe.throwableToString(t)); //log the details

                        //tally
                        EDStatic.tally.add("Subscriptions (since startup)", "List unsuccessful");
                        EDStatic.tally.add("Subscriptions (since last daily report)", "List unsuccessful");
                    }
                }
            }

            //show the form
            writer.write(
                widgets.beginForm("listSub", "GET", tErddapUrl + "/" + Subscriptions.LIST_HTML, "") +
                EDStatic.subscriptionListHtml(tErddapUrl) + "\n" +
                widgets.beginTable(0, 0, "") +
                "<tr>\n" +
                "  <td>Your email address:&nbsp;</td>\n" +
                "  <td>" + widgets.textField("email", "", 
                    60, Subscriptions.EMAIL_LENGTH, tEmail, 
                    "") + "</td>\n" +
                "</tr>\n" +
                "<tr>\n" +
                "  <td>" + widgets.button("submit", null, 
                    EDStatic.clickToSubmit, "Submit", "") + "</td>\n" +
                "</tr>\n" +
                widgets.endTable() +  
                widgets.endForm() +
                EDStatic.subscriptionAbuse + "\n");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * Process erddap/subscriptions/validate.html
     *
     * @param protocol is always subscriptions
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "info") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doValidateSubscription(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String protocol, int datasetIDStartsAt, String userQuery) throws Throwable {

        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, true); //true=names toLowerCase
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);

        //process the query
        String tSubscriptionID = queryMap.get("subscriptionid"); //lowercase since case insensitive
        String tKey            = queryMap.get("key");
        if (tSubscriptionID == null) tSubscriptionID = "";
        if (tKey            == null) tKey            = "";
        String trouble = "";
        if      (tSubscriptionID.length() == 0)            
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionIDUnspecified + "</font>\n";
        else if (!tSubscriptionID.matches("[0-9]{1,10}"))  
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionIDInvalid + "</font>\n";
        if      (tKey.length() == 0)                       
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionKeyUnspecified + "</font>\n";
        else if (!tKey.matches("[0-9]{1,10}"))             
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionKeyInvalid + "</font>\n";

        //display start of web page
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true; 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Validate a Subscription", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, protocol, "validate") +
                EDStatic.subscriptionHtml(tErddapUrl) + "\n");

            if (tSubscriptionID.length() > 0 || tKey.length() > 0) {
                if (trouble.length() > 0) {
                    writer.write("<p><font class=\"warningColor\">" +
                        EDStatic.subscriptionValidateError + "</font>\n" +
                        "<ul>\n" +
                        trouble + "\n" +
                        "</ul>\n");
                } else {
                    //try to validate 
                    try {
                        String message = EDStatic.subscriptions.validate(
                            String2.parseInt(tSubscriptionID), String2.parseInt(tKey));
                        if (message.length() > 0) {
                            writer.write("<p><font class=\"warningColor\">" +
                                EDStatic.subscriptionValidateError + "\n" +
                                "<br>" + message + "</font>\n");

                        } else {writer.write(EDStatic.subscriptionValidateSuccess + "\n");

                            //tally
                            EDStatic.tally.add("Subscriptions (since startup)", "Validate successful");
                            EDStatic.tally.add("Subscriptions (since last daily report)", "Validate successful");
                        }
                    } catch (Throwable t) {
                        writer.write("<p><font class=\"warningColor\">" +
                            EDStatic.subscriptionValidateError + "\n" +
                            "<br>" + XML.encodeAsHTML(MustBe.getShortErrorMessage(t)) + "</font>\n");
                        String2.log("Subscription validate Exception:\n" + MustBe.throwableToString(t));

                        //tally
                        EDStatic.tally.add("Subscriptions (since startup)", "Validate unsuccessful");
                        EDStatic.tally.add("Subscriptions (since last daily report)", "Validate unsuccessful");
                    }
                }
            }

            //show the form
            writer.write(
                widgets.beginForm("validateSub", "GET", tErddapUrl + "/" + Subscriptions.VALIDATE_HTML, "") +
                EDStatic.subscriptionValidateHtml(tErddapUrl) + "\n" +
                widgets.beginTable(0, 0, "") +
                "<tr>\n" +
                "  <td>The subscriptionID:&nbsp;</td>\n" +
                "  <td>" + widgets.textField("subscriptionID", "", 
                    15, 15, tSubscriptionID, "") + "</td>\n" +
                "</tr>\n" +
                "<tr>\n" +
                "  <td>The key:&nbsp;</td>\n" +
                "  <td>" + widgets.textField("key", "", 
                    15, 15, tKey, "") + "</td>\n" +
                "</tr>\n" +
                "<tr>\n" +
                "  <td>" + widgets.button("submit", null, 
                    EDStatic.clickToSubmit, "Submit", "") + "</td>\n" +
                "</tr>\n" +
                widgets.endTable() +  
                widgets.endForm());        

            //link to list of subscriptions
            writer.write(requestSubscriptionListHtml(tErddapUrl, ""));
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }


    /**
     * Process erddap/subscriptions/remove.html
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param protocol is always subscriptions
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "info") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doRemoveSubscription(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String protocol, int datasetIDStartsAt, String userQuery) 
        throws Throwable {

        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, true); //true=names toLowerCase
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);

        //process the query
        String tSubscriptionID = queryMap.get("subscriptionid"); //lowercase since case insensitive
        String tKey            = queryMap.get("key");
        if (tSubscriptionID == null) tSubscriptionID = "";
        if (tKey            == null) tKey            = "";
        String trouble = "";
        if      (tSubscriptionID.length() == 0)            
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionIDUnspecified + "</font>\n";
        else if (!tSubscriptionID.matches("[0-9]{1,10}"))  
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionIDInvalid + "</font>\n";
        if      (tKey.length() == 0)                       
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionKeyUnspecified + "</font>\n";
        else if (!tKey.matches("[0-9]{1,10}"))             
            trouble += "<li><font class=\"warningColor\">" + EDStatic.subscriptionKeyInvalid + "</font>\n";

        //display start of web page
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true; 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Remove a Subscription", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, protocol, "remove") +
                EDStatic.subscriptionHtml(tErddapUrl) + "\n");

            if (tSubscriptionID.length() > 0 || tKey.length() > 0) {
                if (trouble.length() > 0) {
                    writer.write("<p><font class=\"warningColor\">" +
                        EDStatic.subscriptionRemoveError + "</font>\n" +
                        "<ul>\n" +
                        trouble + "\n" +
                        "</ul>\n");
                } else {
                    //try to remove 
                    try {
                        String message = EDStatic.subscriptions.remove(
                            String2.parseInt(tSubscriptionID), String2.parseInt(tKey));
                        if (message.length() > 0) 
                            writer.write("<p><font class=\"warningColor\">" +
                                EDStatic.subscriptionRemoveError + "\n" +
                                "<br>" + message + "</font>\n");
                        else writer.write(EDStatic.subscriptionRemoveSuccess + "\n");

                        //tally
                        EDStatic.tally.add("Subscriptions (since startup)", "Remove successful");
                        EDStatic.tally.add("Subscriptions (since last daily report)", "Remove successful");
                    } catch (Throwable t) {
                        writer.write("<p><font class=\"warningColor\">" +
                            EDStatic.subscriptionRemoveError + "\n" +
                            "<br>" + XML.encodeAsHTML(MustBe.getShortErrorMessage(t)) + "</font>\n");
                        String2.log("Subscription remove Exception:\n" + MustBe.throwableToString(t)); //log the details

                        //tally
                        EDStatic.tally.add("Subscriptions (since startup)", "Remove unsuccessful");
                        EDStatic.tally.add("Subscriptions (since last daily report)", "Remove unsuccessful");
                    }
                }
            }

            //show the form
            writer.write(
                widgets.beginForm("removeSub", "GET", tErddapUrl + "/" + Subscriptions.REMOVE_HTML, "") +
                EDStatic.subscriptionRemoveHtml(tErddapUrl) + "\n" +
                widgets.beginTable(0, 0, "") +
                "<tr>\n" +
                "  <td>The subscriptionID:&nbsp;</td>\n" +
                "  <td>" + widgets.textField("subscriptionID", "", 
                    15, 15, tSubscriptionID, "") + "</td>\n" +
                "</tr>\n" +
                "<tr>\n" +
                "  <td>The key:&nbsp;</td>\n" +
                "  <td>" + widgets.textField("key", "", 
                    15, 15, tKey, "") + "</td>\n" +
                "</tr>\n" +
                "<tr>\n" +
                "  <td>" + widgets.button("submit", null, 
                    EDStatic.clickToSubmit, "Submit", "") + "</td>\n" +
                "</tr>\n" +
                widgets.endTable() +  
                widgets.endForm());        

            //link to list of subscriptions
            writer.write(requestSubscriptionListHtml(tErddapUrl, "") +
                "<br>" + EDStatic.subscriptionRemove2 + "\n");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }


    /**
     * Process erddap/convert/index.html
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param endOfRequest e.g., convert/time.html
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "convert") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doConvert(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, 
        String endOfRequest, int datasetIDStartsAt, String userQuery) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);

        if (endOfRequest.equals("convert") ||
            endOfRequest.equals("convert/")) {
            response.sendRedirect(tErddapUrl + "/convert/index.html");
            return;
        }

        EDStatic.tally.add("Convert (since startup)", endOfRequest);
        EDStatic.tally.add("Convert (since last daily report)", endOfRequest);
        String fileTypeName = File2.getExtension(requestUrl);
        int pft = String2.indexOf(plainFileTypes, fileTypeName);

        if (endOfRequestUrl.equals("index.html")) {
            //fall through
        } else if (endOfRequestUrl.equals("fipscounty.html") ||
                   endOfRequestUrl.equals("fipscounty.txt")) {
            doConvertFipsCounty(request, response, loggedInAs, endOfRequestUrl, userQuery);
            return;
        } else if (endOfRequestUrl.startsWith("fipscounty.") && pft >= 0) {
            try {
                sendPlainTable(loggedInAs, request, response, 
                    EDStatic.fipsCountyTable(), "FipsCountyCodes", fileTypeName);
            } catch (Throwable t) {
                String2.log(MustBe.throwableToString(t));
                throw new SimpleException("The FIPS county service is not available on this ERDDAP.");
            }
            return;
        } else if (endOfRequestUrl.equals("time.html") ||
                   endOfRequestUrl.equals("time.txt")) {
            doConvertTime(request, response, loggedInAs, endOfRequestUrl, userQuery);
            return;
        } else if (endOfRequestUrl.equals("units.html") ||
                   endOfRequestUrl.equals("units.txt")) {
            doConvertUnits(request, response, loggedInAs, endOfRequestUrl, userQuery);
            return;
        } else {
            sendResourceNotFoundError(request, response, "");
            return;
        }

        //display start of web page
        if (reallyVerbose) String2.log("doConvert");
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Convert", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, "convert") +
                EDStatic.convertHtml + "\n" +
                //"<p>Options:\n" +
                "<ul>\n" +
                "<li><a href=\"" + tErddapUrl + "/convert/fipscounty.html\"><b>FIPS County Codes</b></a> - " + 
                    EDStatic.convertFipsCounty + "\n" +
                "<li><a href=\"" + tErddapUrl + "/convert/time.html\"><b>Time</b></a> - " + 
                    EDStatic.convertTime + "\n" +
                "<li><a href=\"" + tErddapUrl + "/convert/units.html\"><b>Units</b></a> - " + 
                    EDStatic.convertUnits + "\n" +
                "</ul>\n");
        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * Process erddap/convert/fipscounty.html and fipscounty.txt.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param endOfRequestUrl   time.html or time.txt
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doConvertFipsCounty(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String endOfRequestUrl, String userQuery) throws Throwable {

        //parse the userQuery
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, false); //true=lowercase keys
        String defaultCode   = "06053";
        String defaultCounty = "CA, Monterey";
        String queryCode     = queryMap.get("code"); 
        String queryCounty   = queryMap.get("county");
        if (queryCode   == null) queryCode = "";
        if (queryCounty == null) queryCounty = "";
        String answerCode    = "";
        String answerCounty  = "";
        String codeTooltip   = "The 5-digit FIPS county code, for example, \"" + defaultCode + "\".";
        //String countyTooltip = "The county name, for example, \"" + defaultCounty + "\".";
        String countyTooltip = "Select a county name.";

        //only 0 or 1 of toCode,toCounty will be true (not both)
        boolean toCounty = queryCode.length() > 0; 
        boolean toCode = !toCounty && queryCounty.length() > 0;

        //a query either succeeds (and sets all answer...) 
        //  or fails (doesn't change answer... and sets tError)

        //process queryCounty
        String tError = null;
        Table fipsTable = null;
        try {
            fipsTable = EDStatic.fipsCountyTable();
        } catch (Throwable t) {
            String2.log(MustBe.throwableToString(t));
            throw new SimpleException("The FIPS county service is not available on this ERDDAP.");
        }
        if (toCode) {
            //process code=,   a toCode query
            int po = ((StringArray)(fipsTable.getColumn(1))).indexOf(queryCounty);
            if (po < 0) {
                tError = "county=\"" + queryCounty + 
                    "\" isn't an exact match of a FIPS county name.";
            } else {
                //success
                answerCounty = queryCounty;
                answerCode   = fipsTable.getColumn(0).getString(po);
            }

        } else if (toCounty) {        
            //process county=,   a toCounty query            
            int po = ((StringArray)(fipsTable.getColumn(0))).indexOf(queryCode);
            if (po < 0) {
                tError = "code=\"" + queryCode + 
                    "\" isn't an exact match of a 5-digit, FIPS county code.";
            } else {
                //success
                answerCode   = queryCode;
                answerCounty = fipsTable.getColumn(1).getString(po);
            }

        } else {
            //no query. use the default values...
        }

        //do the .txt response
        if (endOfRequestUrl.equals("fipscounty.txt")) {

            //throw exception?
            if (tError == null && !toCode && !toCounty)
                tError = "You must specify a code= or county= parameter (for example \"?code=" + 
                defaultCode + "\") at the end of the URL.";
            if (tError != null) 
                throw new SimpleException(tError);

            //respond to a valid request
            OutputStream out = (new OutputStreamFromHttpResponse(request, response, 
                "ConvertTime", ".txt", ".txt")).outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");

            if (toCode) 
                writer.write(answerCode);
            else if (toCounty) 
                writer.write(answerCounty);            
            
            writer.flush(); //essential
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 
            return;
        }

        //do the .html response
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true; 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Convert FIPS County", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, "convert", "FIPS County") +
                "<h2>" + EDStatic.convertFipsCounty + "</h2>\n" +
                EDStatic.convertFipsCountyIntro + "\n");

     
            //Convert from Code to County
            writer.write(
                widgets.beginForm("getCounty", "GET", tErddapUrl + "/convert/fipscounty.html", "") +
                "<b>Convert from</b>\n" + 
                widgets.textField("code", codeTooltip, 6, 6, 
                    answerCode.length() > 0? answerCode :
                    queryCode.length()  > 0? queryCode  : defaultCode, 
                    "") + 
                "\n<b> to a county name. </b>&nbsp;&nbsp;" +
                widgets.button("submit", null, "", 
                    "Convert",
                    "") + 
                "\n");

            if (toCounty) {
                writer.write(tError == null?
                    "<br><font class=\"successColor\">" + 
                        XML.encodeAsHTML(answerCode) + " = " + 
                        XML.encodeAsHTML(answerCounty) + "</font>\n" :
                    "<br><font class=\"warningColor\">" + XML.encodeAsHTML(tError) + "</font>\n");                
            } else {
                writer.write("<br>&nbsp;\n");
            }

            writer.write(widgets.endForm() + "\n");

            //Convert from County to Code
            String selectedCounty = 
                answerCounty.length() > 0? answerCounty :
                queryCounty.length()  > 0? queryCounty  : defaultCounty;
            String options[] = fipsTable.getColumn(1).toStringArray();
            writer.write(
                "<br>&nbsp;\n" +  //necessary for the blank line before start of form (not <p>)
                widgets.beginForm("getCode", "GET", tErddapUrl + "/convert/fipscounty.html", "") +
                "<b>Convert from</b>\n" + 
                //widgets.textField("county", countyTooltip, 35, 50, selectedCounty, "") + 
                widgets.select("county", countyTooltip, 1, options,
                    String2.indexOf(options, selectedCounty), 
                    "onchange=\"this.form.submit();\"") +
                "\n<b> to a 5-digit FIPS code. </b>&nbsp;&nbsp;" +
                //widgets.button("submit", null, "", 
                //    "Convert",
                //    "") + 
                "\n");

            if (toCode) {
                writer.write(tError == null?
                    "<br><font class=\"successColor\">" + 
                        XML.encodeAsHTML(answerCounty) + " = " + 
                        XML.encodeAsHTML(answerCode) + "</font>\n" :
                    "<br><font class=\"warningColor\">" + XML.encodeAsHTML(tError) + "</font>\n");                
            } else {
                writer.write("<br>&nbsp;\n");
            }

            writer.write(widgets.endForm() + "\n");

            //reset the form
            writer.write(
                "<p><a href=\"" + tErddapUrl + 
                    "/convert/fipscounty.html\">" + EDStatic.resetTheForm + "</a>\n" +
                "<p>Or, <a href=\"#computerProgram\">bypass this web page</a>\n" +
                "  and do FIPS county conversions from within a computer program.\n");

            //get the entire list
            writer.write(
                "<p>Or, view the entire FIPS county list in these file types: " +
                plainLinkExamples(tErddapUrl, "/convert/fipscounty", ""));

            //notes  (always non-https urls)
            writer.write(EDStatic.convertFipsCountyNotes);

            //Info about .txt time service option   (always non-https urls)
            writer.write(EDStatic.convertFipsCountyService);

        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * Process erddap/convert/time.html and time.txt.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param endOfRequestUrl   time.html or time.txt
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doConvertTime(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String endOfRequestUrl, String userQuery) throws Throwable {


        //parse the userQuery
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, false); //true=lowercase keys
        //defaultIsoTime, defaultN and defaultUnits are also used in messages.xml convertTimeService
        String defaultIsoTime = "1985-01-02T00:00:00Z";
        String defaultN       = "473472000";
        String defaultUnits   = EDV.TIME_UNITS; //"seconds since 1970-01-01T00:00:00Z";
        String queryIsoTime = queryMap.get("isoTime"); 
        String queryN       = queryMap.get("n");
        String queryUnits   = queryMap.get("units");
        if (queryIsoTime == null) queryIsoTime = "";
        if (queryN       == null) queryN       = "";
        if (queryUnits   == null || queryUnits.length() == 0) 
            queryUnits = defaultUnits;
        String answerIsoTime = "";
        String answerN       = "";
        String answerUnits   = "";
        String unitsTooltip = "The units.  " + EDStatic.convertTimeUnitsHelp;


        //only 0 or 1 of toNumeric,getIsoTime will be true (not both)
        boolean toNumeric = queryIsoTime.length() > 0; 
        boolean toString = !toNumeric && queryN.length() > 0;

        //a query either succeeds (and sets all answer...) 
        //  or fails (doesn't change answer... and sets tError)

        //process queryUnits
        double tbf[] = null;
        String tError = null;
        try {
            tbf = Calendar2.getTimeBaseAndFactor(queryUnits);
        } catch (Throwable t) {
            tError = t.getMessage();
        }

        double epochSeconds; //will be valid if no error
        if (tError == null) {
            if (toNumeric) {
                //process isoTime=,   a toNumeric query
                epochSeconds = Calendar2.safeIsoStringToEpochSeconds(queryIsoTime); 
                if (Double.isNaN(epochSeconds)) {
                    tError = "isoTime=\"" + queryIsoTime + 
                        "\" isn't a valid ISO 8601 time string (YYYY-MM-DDThh:mm:ssZ).";
                } else {
                    //success
                    answerIsoTime = queryIsoTime;
                    answerUnits = queryUnits;
                    double tN = Calendar2.epochSecondsToUnitsSince(tbf[0], tbf[1], epochSeconds);
                    answerN = tN == Math2.roundToLong(tN)? 
                        "" + Math2.roundToLong(tN) : //so no .0 at end
                        "" + tN;
                }

            } else if (toString) {        
                //process n=,   a toString query            
                double tN = String2.parseDouble(queryN);
                if (Double.isNaN(tN)) {
                    tError = "n=\"" + tN + "\" isn't a valid number.";
                } else {
                    //success
                    answerUnits = queryUnits;
                    epochSeconds = Calendar2.unitsSinceToEpochSeconds(tbf[0], tbf[1], tN);
                    answerIsoTime = Calendar2.safeEpochSecondsToIsoStringTZ(epochSeconds, "[error]");
                    answerN = tN == Math2.roundToLong(tN)? 
                        "" + Math2.roundToLong(tN) : //so no .0 at end
                        "" + tN;
                }

            } else {
                //no query. use the default values...
            }
        }

        //do the .txt response
        if (endOfRequestUrl.equals("time.txt")) {

            //throw exception?
            if (tError == null && !toNumeric && !toString)
                tError = "You must specify a parameter (for example \"?n=" + 
                defaultN + "\") at the end of the URL.";
            if (tError != null) 
                throw new SimpleException(tError);

            //respond to a valid request
            OutputStream out = (new OutputStreamFromHttpResponse(request, response, 
                "ConvertTime", ".txt", ".txt")).outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");

            if (toNumeric) 
                writer.write(answerN);
            else if (toString) 
                writer.write(answerIsoTime);            
            
            writer.flush(); //essential
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 
            return;
        }

        //do the .html response
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true; 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Convert Time", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, "convert", "time") +
                "<h2>" + EDStatic.convertTime + "</h2>\n" +
                EDStatic.convertTimeIntro + "\n");

     
            //Convert from n units to an ISO String Time
            writer.write(
                "<br>&nbsp;\n" +  //necessary for the blank line before start of form (not <p>)
                widgets.beginForm("getIsoString", "GET", tErddapUrl + "/convert/time.html", "") +
                //"<b>Convert from a Numeric Time to a String Time</b>\n" +
                //"<br>
                "<b>Convert from</b>\n" + //n=\n" +
                widgets.textField("n", 
                    "A number.  For example, \"" + defaultN + "\".",
                    15, 20, 
                    answerN.length() > 0? answerN :
                    queryN.length()  > 0? queryN  : defaultN, 
                    "") + 
                "\n" + //units=\n" + 
                widgets.textField("units", unitsTooltip,
                    37, 42, 
                    answerUnits.length() > 0? answerUnits :
                    queryUnits.length()  > 0? queryUnits  : defaultUnits, 
                    "") + 
                "<b> to a String Time. </b>&nbsp;&nbsp;" +
                widgets.button("submit", null, "", 
                    "Convert",
                    "") + 
                "\n");

            if (toString) {
                writer.write(tError == null?
                    "<br><font class=\"successColor\">" + 
                        XML.encodeAsHTML(answerN) + " " + 
                        XML.encodeAsHTML(answerUnits) + " = " + 
                        XML.encodeAsHTML(answerIsoTime) + "</font>\n" :
                    "<br><font class=\"warningColor\">" + XML.encodeAsHTML(tError) + "</font>\n");                
            } else {
                writer.write("<br>&nbsp;\n");
            }

            writer.write(widgets.endForm() + "\n");

            //Convert from an ISO String Time to Numeric Time n units
            writer.write(
                "<br>&nbsp;\n" +  //necessary for the blank line before start of form (not <p>)
                widgets.beginForm("getEpochSeconds", "GET", tErddapUrl + "/convert/time.html", "") +
                //"<b>Convert from a String Time to a Numeric Time</b>\n" +
                //"<br>" +
                "<b>Convert from</b>\n" + // isoTime=\n" + 
                widgets.textField("isoTime", 
                    "The ISO String time.  For example, \"" + defaultIsoTime + "\".",
                    22, 27, 
                    answerIsoTime.length() > 0? answerIsoTime :
                    queryIsoTime.length()  > 0? queryIsoTime  : defaultIsoTime, 
                    "") + 
                //"\n" + //" to units=" +
                "<b> to a Numeric Time in </b>" +
                widgets.textField("units", unitsTooltip,
                    37, 42, 
                    answerUnits.length() > 0? answerUnits :
                    queryUnits.length()  > 0? queryUnits  : defaultUnits, 
                    "") + 
                "<b>.</b>&nbsp;&nbsp;\n" +
                widgets.button("submit", null, "", 
                    "Convert",
                    "") + 
                "\n");

            if (toNumeric) {
                writer.write(tError == null?
                    "<br><font class=\"successColor\">" + 
                        XML.encodeAsHTML(answerIsoTime) + " = " + 
                        XML.encodeAsHTML(answerN) + " " + 
                        XML.encodeAsHTML(answerUnits) + "</font>\n" :
                    "<br><font class=\"warningColor\">" + XML.encodeAsHTML(tError) + "</font>\n");
            } else {
                writer.write("<br>&nbsp;\n");
            }

            writer.write(widgets.endForm() + "\n");

            //reset the form
            writer.write(
                "<p><a href=\"" + tErddapUrl + 
                    "/convert/time.html\">" + EDStatic.resetTheForm + "</a>\n" +
                "<p>Or, <a href=\"#computerProgram\">bypass this web page</a>\n" +
                "  and do time conversions from within a computer program.\n");

            //notes  (always non-https urls)
            writer.write(EDStatic.convertTimeNotes);

            //Info about .txt time service option   (always non-https urls)
            writer.write(EDStatic.convertTimeService);

        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }


    /**
     * Process erddap/convert/units.html and units.txt.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param endOfRequestUrl   time.html or time.txt
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doConvertUnits(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String endOfRequestUrl, String userQuery) throws Throwable {


        //parse the userQuery
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, false); //true=lowercase keys
        String tUdunits = queryMap.get("UDUNITS"); 
        String tUcum    = queryMap.get("UCUM");
        if (tUdunits == null) tUdunits = "";
        if (tUcum    == null) tUcum    = "";
        String rUcum    = tUdunits.length() == 0? "" : EDUnits.udunitsToUcum(tUdunits);
        String rUdunits = tUcum.length()    == 0? "" : EDUnits.ucumToUdunits(tUcum);

        //do the .txt response
        if (endOfRequestUrl.equals("units.txt")) {

            //throw exception?
            if (tUdunits.length() == 0 && tUcum.length() == 0) {
                throw new SimpleException("Query error: Missing parameter (UDUNITS or UCUM).");
            }

            //respond to a valid request
            OutputStream out = (new OutputStreamFromHttpResponse(request, response, 
                "ConvertUnits", ".txt", ".txt")).outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");

            if (tUdunits.length() > 0) 
                writer.write(rUcum);
            else if (tUcum.length() > 0) 
                writer.write(rUdunits);            
            
            writer.flush(); //essential
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 
            return;
        }

        //do the .html response
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true; 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "Convert Units", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, "convert", "units") +
                "<h2>" + EDStatic.convertUnits + "</h2>\n" +
                EDStatic.convertUnitsIntro +
                "\nOn this ERDDAP, most/all datasets use " + EDStatic.units_standard + ".\n" +
                "\n<br>In tabledap requests, you can \n" +
                  "<a href=\"#unitsFilter\">request UDUNITS or UCUM</a>.\n");
     
            //show the forms
            writer.write(
                "<br>&nbsp;\n" + //necessary for the blank line before start of form (not <p>)
                widgets.beginForm("getUcum", "GET", tErddapUrl + "/convert/units.html", "") +
                "<b>Convert from UDUNITS to UCUM</b>\n" +
                "<br>UDUNITS:\n" + 
                widgets.textField("UDUNITS", 
                    "For example, degree_C meter-1",
                    40, 100, 
                    tUdunits.length() > 0? tUdunits : 
                    rUdunits.length() > 0? rUdunits :
                    "degree_C meter-1", 
                    "") + 
                " " +
                widgets.button("submit", null, "", 
                    "Convert to UCUM",
                    "") + 
                "\n");

            if (tUdunits.length() == 0) 
                writer.write(
                    "<br>&nbsp;\n");
            else 
                writer.write(
                    "<br><font class=\"successColor\">UDUNITS \"" + XML.encodeAsHTML(tUdunits) + 
                        "\" &rarr; UCUM \"" + rUcum + "\"</font>\n");

            writer.write(
                widgets.endForm() +
                "\n");

            writer.write(
                "<br>&nbsp;\n" + //necessary for the blank line before start of form (not <p>)
                widgets.beginForm("getUdunits", "GET", tErddapUrl + "/convert/units.html", "") +
                "<b>Convert from UCUM to UDUNITS</b>\n" +
                "<br>UCUM:\n" + 
                widgets.textField("UCUM", 
                    "For example, Cel.m-1",
                    40, 100, 
                    tUcum.length() > 0? tUcum : 
                    rUcum.length() > 0? rUcum : 
                    "Cel.m-1", 
                    "") + 
                " " +
                widgets.button("submit", null, "", 
                    "Convert to UDUNITS",
                    "") + 
                "\n");

            if (tUcum.length() == 0) 
                writer.write(
                    "<br>&nbsp;\n");
            else 
                writer.write(
                    "<br><font class=\"successColor\">UCUM \"" + XML.encodeAsHTML(tUcum) + 
                        "\" &rarr; UDUNITS \"" + rUdunits + "\"</font>\n");            

            writer.write(widgets.endForm());
            writer.write('\n');
            
            writer.write(EDStatic.convertUnitsNotes);
            writer.write('\n');

            //Info about service / .txt option   (always non-https urls)
            writer.write(EDStatic.convertUnitsService);
            writer.write('\n');


            //info about syntax differences 
            writer.write(EDStatic.convertUnitsComparison);
            writer.write('\n');


            //info about tabledap unitsFilter &units("UCUM") 
            writer.write(EDStatic.convertUnitsFilter);
            writer.write('\n');

            } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * Process erddap/post/...
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param endOfRequest e.g., convert/time.html
     * @param datasetIDStartsAt is the position right after the / at the end of the protocol
     *    (always "convert") in the requestUrl
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doPostPages(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, 
        String endOfRequest, int datasetIDStartsAt, String userQuery) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String requestUrl = request.getRequestURI();  //post EDStatic.baseUrl, pre "?"
        String endOfRequestUrl = datasetIDStartsAt >= requestUrl.length()? "" : 
            requestUrl.substring(datasetIDStartsAt);

        boolean postActive = 
            EDStatic.PostSurgeryDatasetID.length() > 0 &&
            EDStatic.PostDetectionDatasetID.length() > 0 &&
            tableDatasetHashMap.get(EDStatic.PostSurgeryDatasetID) != null &&
            tableDatasetHashMap.get(EDStatic.PostDetectionDatasetID) != null; 
        if (!postActive)
            sendResourceNotFoundError(request, response, "Currently, the POST datasets aren't available.");

        if (EDStatic.postShortDescriptionActive) {
            //post/index.html is inactive and redirects to (tErddapUrl)/index.html
            if (endOfRequest.equals("post") ||
                endOfRequest.equals("post/") || 
                endOfRequest.equals("post/index.html")) {
                response.sendRedirect(tErddapUrl + "/index.html");
                return;
            }
        } else {
            //if no document specified, redirect to /post/index.html (else fall through)
            if (endOfRequest.equals("post") ||
                endOfRequest.equals("post/")) {
                response.sendRedirect(tErddapUrl + "/post/index.html");
                return;
            }
        }

        EDStatic.tally.add("POST (since startup)", endOfRequest);
        EDStatic.tally.add("POST (since last daily report)", endOfRequest);

        if (endOfRequestUrl.equals("index.html")) {
            //fall through
        } else if (endOfRequestUrl.startsWith("license.html")) {
            doPostLicense(request, response, loggedInAs);
            return;
        } else if (endOfRequestUrl.startsWith("subset.html")) {
            doPostSubset(request, response, loggedInAs, userQuery);
            return;
        } else {
            sendResourceNotFoundError(request, response, "");
            return;
        }

        //display start of web page
        if (reallyVerbose) String2.log("doPost");
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "POST", out); 
        try {
            writer.write(
                EDStatic.youAreHere(loggedInAs, "POST"));

            writer.write(getPostIndexHtml(loggedInAs, tErddapUrl));

        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }


    /**
     * This returns the left side html for the post index page (or the home page of the post erddap).
     */
    public String getPostIndexHtml(String loggedInAs, String tErddapUrl) throws Throwable {

        StringBuilder sb = new StringBuilder();
        sb.append(EDStatic.PostIndex1Html(tErddapUrl));

        if (loggedInAs == null) 
            sb.append(EDStatic.PostIndex2Html());

        sb.append(EDStatic.PostIndex3Html(tErddapUrl));

        return sb.toString();
    }

    
    /**
     * Process erddap/post/license.html
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doPostLicense(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);

        boolean postActive = 
            EDStatic.PostSurgeryDatasetID.length() > 0 &&
            EDStatic.PostDetectionDatasetID.length() > 0 &&
            tableDatasetHashMap.get(EDStatic.PostSurgeryDatasetID) != null &&
            tableDatasetHashMap.get(EDStatic.PostDetectionDatasetID) != null; 
        EDDTable eddSurgery = (EDDTable)tableDatasetHashMap.get(EDStatic.PostSurgeryDatasetID);
        if (!postActive)
            sendResourceNotFoundError(request, response, "Currently, the POST datasets aren't available.");

        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "POST License", out); 
        try {
            if (EDStatic.postShortDescriptionActive)
                writer.write(
                    EDStatic.youAreHere(loggedInAs, "POST License"));
            else writer.write(
                    EDStatic.youAreHere(loggedInAs, "post", "license")); //"post" must be lowercase for the link to work

            //show the POST license
            writer.write(
                "By accessing the POST data, you are agreeing to the terms of the POST License:\n" +
                "<pre>\n" +
                XML.encodeAsPreHTML(eddSurgery.combinedGlobalAttributes().getString("license"), 80) +
                "</pre>\n");

        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * Process erddap/post/subset.html
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param userQuery  post "?", still percentEncoded, may be null.
     * @throws Throwable if trouble
     */
    public void doPostSubset(HttpServletRequest request, HttpServletResponse response, 
        String loggedInAs, String userQuery) throws Throwable {

        //BOB: this is similar to EDDTable.respondToSubsetQuery()

        //constants
        String ANY = "(ANY)";
        String surgeryYear = "surgery_year";

        //post active?
        EDDTable surgeryEdd   = (EDDTable)(EDStatic.PostSurgeryDatasetID.length() == 0? null :
            tableDatasetHashMap.get(EDStatic.PostSurgeryDatasetID));
        EDDTable detectionEdd = (EDDTable)(EDStatic.PostDetectionDatasetID.length() == 0? null :
            tableDatasetHashMap.get(EDStatic.PostDetectionDatasetID));
        boolean postActive = surgeryEdd != null && detectionEdd != null; 
        if (!postActive) {
            sendResourceNotFoundError(request, response, "Currently, the POST datasets aren't available.");
            return;
        }
        if (EDStatic.PostSubset.length == 0) 
            throw new SimpleException("<PostSubset> wasn't specified in setup.xml.");
        if (!surgeryEdd.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { 
            EDStatic.redirectToLogin(loggedInAs, response, EDStatic.PostSurgeryDatasetID);
            return;
        }
        if (!detectionEdd.isAccessibleTo(EDStatic.getRoles(loggedInAs))) { 
            EDStatic.redirectToLogin(loggedInAs, response, EDStatic.PostDetectionDatasetID);
            return;
        }
        //parse the userQuery and create the bigUserDapQuery and smallUserDapQuery
        HashMap<String, String> queryMap = EDD.userQueryHashMap(userQuery, false); //true=lowercase keys
        int lastP = String2.indexOf(EDStatic.PostSubset, queryMap.get(".last")); //pName of last changed select option
        String param[] = new String[EDStatic.PostSubset.length];
        StringBuilder bigUserDapQuery   = new StringBuilder();
        StringBuilder smallUserDapQuery = new StringBuilder();
        StringBuilder countsConstraints = new StringBuilder();
        StringBuilder countsQuery = new StringBuilder();
        for (int p = 0; p < EDStatic.PostSubset.length; p++) {
            String pName = EDStatic.PostSubset[p];
            param[p] = queryMap.get(pName); 
            if (param[p] == null || param[p].equals(ANY)) {
                param[p] = null;
            } else if (param[p].length() >= 2 &&
                       param[p].charAt(0) == '"' && 
                       param[p].charAt(param[p].length() - 1) == '"') {
                //remove begin/end quotes
                param[p] = param[p].substring(1, param[p].length() - 1);
            }
            //if last selection was ANY, last is irrelevant
            if (p == lastP && param[p] == null)
                lastP = -1;

            if (param[p] == null) {
                //nothing

            } else if (pName.equals(surgeryYear)) {
                if (param[p].length() > 0) {
                    int year = String2.parseInt(param[p]);
                    String tq = year > 1800 && year < 2200?
                        EDStatic.pEncode("&surgery_time>=" + year    + "-01-01" + 
                                         "&surgery_time<" + (year+1) + "-01-01") :
                        "NaN";
                    smallUserDapQuery.append(tq);
                    if (p != lastP) {
                        bigUserDapQuery.append(tq);
                        countsQuery.append(tq);
                        countsConstraints.append(
                            (countsConstraints.length() > 0? " and " : "") +
                            pName + "=" + SSR.minimalPercentEncode("\"" + param[p] + "\""));
                    }
                }
            } else { //all are strings
                if (param[p] != null) {
                    String tq = "&" + pName + "=" + SSR.minimalPercentEncode("\"" + param[p] + "\"");
                    smallUserDapQuery.append(tq);
                    if (p != lastP) {
                        bigUserDapQuery.append(tq);
                        countsQuery.append(tq);
                        countsConstraints.append(
                            (countsConstraints.length() > 0? " and " : "") +
                            pName + "=\"" + param[p] + "\"");
                    }
                }
            }
        }
        if (reallyVerbose) 
            String2.log(
                "  bigUserDapQuery=" + bigUserDapQuery + 
                "\n  smallUserDapQuery=" + smallUserDapQuery);

        //get the corresponding surgery table subset (or null if no data or trouble)
        String tDir = surgeryEdd.cacheDirectory();
        String tBigFileName   = "subset_" + surgeryEdd.suggestFileName(loggedInAs, bigUserDapQuery.toString(), ".nc");
        String tSmallFileName = "subset_" + surgeryEdd.suggestFileName(loggedInAs, smallUserDapQuery.toString(), ".nc");
        Table bigTable = null;    //more than what user actually selected if lastP is active
        Table smallTable = null;  //what user actually selected
        String surgeryNcUrl = "/tabledap/" + EDStatic.PostSurgeryDatasetID + ".nc";

        //read bigTable from cached file?
        if (File2.isFile(tDir + tBigFileName + ".nc")) {
            try {
                //read from the file
                bigTable = new Table();
                bigTable.readFlatNc(tDir + tBigFileName + ".nc", null, 0);
                //String2.log("data from cached file");
            } catch (Throwable t) {
                String2.log("reading file=" + tDir + tBigFileName + ".nc" + "failed:\n" +
                    MustBe.throwableToString(t));
                bigTable = null;
                File2.delete(tDir + tBigFileName + ".nc");
            }
        }

        //generate bigTable via bigUserDapQuery and getDataForDapQuery?
        if (bigTable == null) {
            try {
                TableWriterAllWithMetadata twawm = surgeryEdd.getTwawmForDapQuery(
                    loggedInAs, surgeryNcUrl, bigUserDapQuery.toString());  
                surgeryEdd.saveAsFlatNc(tDir + tBigFileName + ".nc", twawm); //internally, it writes to temp file, then rename to cacheFullName
                bigTable = twawm.cumulativeTable();
                //String2.log("data from surgeryEdd");
            } catch (Throwable t) {
                //e.g., no Data will occur if user generated invalid query (e.g., not by using web form)
                String2.log("creating bigTable from bigUserDapQuery=" + bigUserDapQuery + " failed:\n" +
                    MustBe.throwableToString(t));
            }
        }

        //is smallTable same as bigTable?
        if (lastP < 0) {
            smallTable = bigTable;
        } else {
            //read smallTable from cached file?
            if (File2.isFile(tDir + tSmallFileName + ".nc")) {
                try {
                    //read from the file
                    smallTable = new Table();
                    smallTable.readFlatNc(tDir + tSmallFileName + ".nc", null, 0);
                    //String2.log("data from cached file");
                } catch (Throwable t) {
                    String2.log("reading file=" + tDir + tSmallFileName + ".nc" + "failed:\n" +
                        MustBe.throwableToString(t));
                    smallTable = null;
                    File2.delete(tDir + tSmallFileName + ".nc");
                }
            }

            //generate smallTable via smallUserDapQuery and getDataForDapQuery?
            if (smallTable == null) {
                try {
                    TableWriterAllWithMetadata twawm = surgeryEdd.getTwawmForDapQuery(
                        loggedInAs, surgeryNcUrl, smallUserDapQuery.toString());  
                    surgeryEdd.saveAsFlatNc(tDir + tSmallFileName + ".nc", twawm); //internally, it writes to temp file, then rename to cacheFullName
                    smallTable = twawm.cumulativeTable();
                    //String2.log("data from surgeryEdd");
                } catch (Throwable t) {
                    //e.g., no Data will occur if user generated invalid query (e.g., not by using web form)
                    String2.log("creating smallTable from smallUserDapQuery=" + smallUserDapQuery + " failed:\n" +
                        MustBe.throwableToString(t));
                }
            }
        }


        //show the .html response/form
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true; 
        OutputStream out = getHtmlOutputStream(request, response);
        Writer writer = getHtmlWriter(loggedInAs, "POST Subset", out); 

        try {
            
            //you are here
            if (EDStatic.postShortDescriptionActive)
                writer.write(
                    EDStatic.youAreHere(loggedInAs, "POST Subset"));
            else writer.write(
                    EDStatic.youAreHere(loggedInAs, "post", "subset")); //"post" must be lowercase for link to work

            writer.write(
                "<b>This interactive web page helps you select a subset of the POST surgery\n" +
                EDStatic.htmlTooltipImage(loggedInAs, XML.encodeAsPreHTML(surgeryEdd.summary(), 100)) + "\n" +
                "and detection\n" +
                EDStatic.htmlTooltipImage(loggedInAs, XML.encodeAsPreHTML(detectionEdd.summary(), 100)) + "\n" +
                "data and view maps\n" +
                "<br>and tables of the selected data.</b>\n" +
                "By accessing the POST data, you are agreeing to the terms of the\n" +
                "<a href=\"" + tErddapUrl + "/post/license.html\">POST License</a>.\n" +
                "\n" +
                "<br>&nbsp;\n"); //necessary for the blank line before start of form (not <p>) 
            
            //if noData/invalid request tell user and reset all
            if (bigTable == null) {
                //error message?
                writer.write("<font class=\"warningColor\">" + EDStatic.THERE_IS_NO_DATA + 
                    " The form below has been reset.</font>\n");

                //reset all
                Arrays.fill(param, ANY);
                bigUserDapQuery = new StringBuilder();
                tBigFileName = "subset_" + surgeryEdd.suggestFileName(loggedInAs, bigUserDapQuery.toString(), ".nc");

                if (File2.isFile(tDir + tBigFileName + ".nc")) {
                    //read bigTable from cached file?
                    bigTable = new Table();
                    bigTable.readFlatNc(tDir + tBigFileName + ".nc", null, 0);
                } else {
                    //or get the data via bigUserDapQuery
                    TableWriterAllWithMetadata twawm = surgeryEdd.getTwawmForDapQuery(
                        loggedInAs, surgeryNcUrl, bigUserDapQuery.toString());  
                    surgeryEdd.saveAsFlatNc(tDir + tBigFileName + ".nc", twawm); //internally, it writes to temp file, then rename to cacheFullName
                    bigTable = twawm.cumulativeTable();
                }

                //reset other things
                smallTable = null; //triggers changes below
            }
            if (smallTable == null) {
                smallUserDapQuery = bigUserDapQuery;
                tSmallFileName = tBigFileName;
                smallTable = bigTable;
                lastP = -1;
            }

            //Select a subset of surgeries
            writer.write(
                widgets.beginForm("f1", "GET", tErddapUrl + "/post/subset.html", "") + "\n" +
                "<b>Select a subset of tagged animals.</b>\n" +
                "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<font class=\"subduedColor\">" +
                    "(Number of animals currently selected: " + smallTable.nRows() + ")</font>\n" +
                "  <br>Make as many selections as you want, in any order.\n" +
                "   Each selection changes the other options (and the maps and data below) accordingly.\n" +
                widgets.beginTable(0, 0, ""));  

            StringBuilder newDapQuery = new StringBuilder();
            boolean allData = true;
            for (int p = 0; p < EDStatic.PostSubset.length; p++) {
                String pName = EDStatic.PostSubset[p];
                boolean isSurgeryYear = pName.equals(surgeryYear);
                StringArray sa; //work on a copy
                if (isSurgeryYear) {
                    //if isSurgeryYear, convert iso times to just years
                    PrimitiveArray pa = p == lastP? 
                        bigTable.findColumn("surgery_time") :
                        smallTable.findColumn("surgery_time");
                    int n = pa.size();
                    sa = new StringArray(n, false);
                    for (int row = 0; row < n; row++) {
                        double epochSec = pa.getDouble(row);
//!!!for now, better to suppress NaN time (since user can request those tags anyway)
                        if (!Double.isNaN(epochSec))
                            sa.add(Calendar2.epochSecondsToIsoStringT(epochSec).substring(0, 4));
                        //sa.add(Double.isNaN(epochSec)? "NaN" : 
                        //    Calendar2.epochSecondsToIsoStringT(epochSec).substring(0, 4));
                    }
                } else {
                    sa = new StringArray(p == lastP?
                        bigTable.findColumn(pName) :
                        smallTable.findColumn(pName)); 
                } 
                sa.sortIgnoreCase();
                //int nBefore = sa.size();
                sa.removeDuplicates();
                //String2.log(pName + " nBefore=" + nBefore + " nAfter=" + sa.size());
                sa.addString(0, ANY);
                String saa[] = sa.toStringArray();
                int which = param[p] == null? 0 : String2.indexOf(saa, param[p]);
                if (which < 0)
                    which = 0;
                if (which > 0) {
                    allData = false;
                    if (isSurgeryYear) {
                        if (sa.get(which).equals("NaN")) {
                            newDapQuery.append("&surgery_time=NaN");
                        } else {
                            int yr = String2.parseInt(sa.get(which));
                            newDapQuery.append(EDStatic.pEncode(
                                "&surgery_time>=" + yr      + "-01-01" +
                                "&surgery_time<" + (yr + 1) + "-01-01"));
                        }
                    } else {
                        newDapQuery.append("&" + SSR.minimalPercentEncode(pName) + "=" + 
                            SSR.minimalPercentEncode("\"" + param[p] + "\""));
                    }
                }

                //String2.log("pName=" + pName + " which=" + which);
                writer.write(
                    "<tr>\n" +
                    "  <td nowrap>&nbsp;&nbsp;&nbsp;&nbsp;" + pName + "&nbsp;</td>\n" +
                    "  <td nowrap>\n");

                if (p == lastP) 
                    writer.write(
                        "  " + widgets.beginTable(0, 0, "") + "\n" +
                        "  <tr>\n" +
                        "  <td nowrap>\n");

                writer.write(
                    "=" +
                    widgets.select(pName, "", 1,
                        saa, which, "onchange='mySubmit(\"" + pName + "\");'"));

                if (p == lastP) { 
                    writer.write(
                        "      </td>\n" +
                        "      <td>\n" +
                        "<img src=\"" + widgets.imageDirUrl + "minus.gif\"\n" +
                        "  " + widgets.completeTooltip("Select the previous item.") +
                        "  alt=\"-\" " + 
                        //onMouseUp works much better than onClick and onDblClick
                        "  onMouseUp='\n" +
                        "   var sel=document.f1." + pName + ";\n" +
                        "   if (sel.selectedIndex>0) {\n" + 
                        "    sel.selectedIndex--;\n" +
                        "    mySubmit(\"" + pName + "\");\n" +
                        "   }' >\n" + 
                        "      </td>\n" +

                        "      <td>\n" +
                        "<img src=\"" + widgets.imageDirUrl + "plus.gif\"\n" +
                        "  " + widgets.completeTooltip("Select the next item.") +
                        "  alt=\"+\" " + 
                        //onMouseUp works much better than onClick and onDblClick
                        "  onMouseUp='\n" +
                        "   var sel=document.f1." + pName + ";\n" +
                        "   if (sel.selectedIndex<sel.length-1) {\n" + //no action if at last item
                        "    sel.selectedIndex++;\n" +
                        "    mySubmit(\"" + pName + "\");\n" +
                        "   }' >\n" + 
                        "      </td>\n" +

                        "    </tr>\n" +
                        "    " + widgets.endTable());
                }

                writer.write(
                    //write hidden last widget
                    (p == lastP? widgets.hidden("last", pName) : "") +

                    //end of select td (or its table)
                    "      </td>\n");

                //n options
                writer.write(
                    "  <td nowrap>&nbsp;" +
                    (which == 0 || p == lastP? 
                        "<font class=\"subduedColor\">(" + 
                            (sa.size() - 1) + " option" + 
                            (sa.size() == 2? ": " + sa.get(1) : "s") + 
                            ")</font>\n" : 
                        ""));

                //mention PostSampleTag if it's an option
                if (pName.equals("unique_tag_id")) {
                    int samplePo = sa.indexOf(EDStatic.PostSampleTag);
                    if (samplePo >= 0 && which != samplePo) {
                        writer.write("&nbsp;&nbsp;" + 
                            widgets.button("button", null, 
                                XML.encodeAsHTML(EDStatic.PostSampleTag) + " is an interesting tag.", 
                                "An interesting tag.",
                                "onclick='f1." + pName + ".selectedIndex=" + samplePo + ";" +
                                    "mySubmit(\"" + pName + "\");'"));
                    }
                }


                writer.write(
                    "  </td>\n" +
                    "</tr>\n");
            }

            writer.write(
                "</table>\n" +
                "\n");

            //View the graph and/or data?
            writer.write(
                "<p><b>View:</b>\n");
            String viewParam[]    = {"surgeryMap",          "detectionMap",  "surgeryCounts",  "surgeryData",  "detectionData"};
            String viewTitle[]    = {"Surgery/Release Map", "Detection Map", "Surgery Counts", "Surgery Data", "Detection Data"};
            boolean viewDefault[] = {true,                  true,            false,            true,           false};
            boolean viewChecked[] = new boolean[viewParam.length]; //will be set below
            String warn = 
                "<p>This may involve lots of data and may be slow." +
                "<br>Consider using this only when you need it.";
            String viewTooltip[] = {
                "View a map of the locations where the selected tagged" +
                "<br>animals were released.", 

                "View a map of the locations where the selected tagged" +
                "<br>animals were released or detected." +
                warn, 

                "View a table with counts of the matching surgery data." +
                "<br>The table shows all of the values of the last-selected" +
                "<br>variable, not just the last selected value.",

                "View a table of surgery data for the selected tagged animals." +
                "<p>The longitude, latitude, and time columns indicate the" +
                "<br>animal's release location and time.",

                "View a table of detection data for all of the selected\n" +
                "<br>tagged animals." +
                "<p>In almost all cases, the first detection is for the\n" +
                "<br>animal's release location and time." +
                warn};
 
            for (int v = 0; v < viewParam.length; v++) {
                String val = userQuery == null || userQuery.length() == 0? 
                    "" + viewDefault[v] : queryMap.get("." + viewParam[v]);
                viewChecked[v] = "true".equals(val); //val may be null
                writer.write(
                    "&nbsp;&nbsp;&nbsp;\n" + 
                    widgets.checkbox(viewParam[v], "", 
                        viewChecked[v], "true", viewTitle[v], 
                        "onclick='mySubmit(null);'") +  //IE doesn't trigger onchange for checkbox
                    EDStatic.htmlTooltipImage(loggedInAs, viewTooltip[v]));
            }

            //mySubmit   (greatly reduces the length of the query -- just essential info)
            writer.write(
                HtmlWidgets.PERCENT_ENCODE_JS +
                "<script type=\"text/javascript\"> \n" +
                "function mySubmit(tLast) { \n" +
                "  try { \n" +
                "    var d = document; \n" +
                "    var q = \"\"; \n" +
                "    var w; \n");
            for (int p = 0; p < EDStatic.PostSubset.length; p++) {
                String pName = EDStatic.PostSubset[p];
                String quotes = pName.equals("surgery_year")? "" : "\\\"";
                writer.write(
                "    w = d.f1." + pName + ".selectedIndex; \n" +
                "    if (w > 0) q += \"&" + pName + 
                    "=\" + percentEncode(\"" + quotes + "\" + d.f1." + pName + 
                        ".options[w].text + \"" + quotes + "\"); \n");
            }
            for (int p = 0; p < viewParam.length; p++) {
                String pName = viewParam[p];
                writer.write(
                "    if (d.f1." + pName + ".checked) q += \"&." + pName + "=true\"; \n");
            }            
            //last
            writer.write(
                "    if ((tLast == null || tLast == undefined) && d.f1.last != undefined) tLast = d.f1.last.value; \n" + //get from hidden widget?
                "    if (tLast != null && tLast != undefined) q += \"&.last=\" + tLast; \n");

            //query must be something, else checkboxes reset to defaults
            writer.write( 
                "    if (q.length == 0) q += \"&." + viewParam[0] + "=false\"; \n"); //javascript uses length, not length()

            //submit the query
            writer.write(
                "    window.location=\"" + tErddapUrl + "/post/subset.html?\" + q;\n" + 
                "  } catch (e) { \n" +
                "    alert(e); \n" +
                "    return \"\"; \n" +
                "  } \n" +
                "} \n" +
                "</script> \n");  
            writer.write(HtmlWidgets.ifJavaScriptDisabled);

            //endForm
            writer.write(widgets.endForm() + "\n");

            //set initial focus to lastP select widget    throws error
            if (lastP >= 0) 
                writer.write(
                    "<script type=\"text/javascript\">document.f1." + EDStatic.PostSubset[lastP] + ".focus();</script>\n");
            
            //RESULTS
            writer.write(
                "<a name=\"map\">&nbsp;</a><hr>\n" +
                widgets.beginTable(0, 0, "width=\"100%\"") +
                "<tr>\n" +
                "  <td width=\"50%\" valign=\"top\">\n"); 

            //0 = map = Surgery Map
            String graphDapQuery = XML.encodeAsHTML(
                "longitude,latitude,time" + //yes, release "time" (not surgery_time) appropriate for release location
                newDapQuery.toString() + 
                "&.draw=markers&.colorBar=|D||||");
            writer.write(
                "<b>Surgery/Release Map</b>\n" +
                EDStatic.htmlTooltipImage(loggedInAs, viewTooltip[0]) +
                "<br>(<a href=\"" + tErddapUrl + "/tabledap/" + EDStatic.PostSurgeryDatasetID + ".graph?" +
                    graphDapQuery + "\">" +
                    "Refine the map and/or download the image</a>)\n");
            if (viewChecked[0]) {  
                writer.write(
                    "<br><img width=\"" + EDStatic.imageWidths[1] + 
                        "\" height=\"" + EDStatic.imageHeights[1] + "\" " +
                        "alt=\"Post-surgery release locations and times.\" " +
                        "title=\"Post-surgery release locations and times.\" " +
                        "src=\"" + tErddapUrl + "/tabledap/" + EDStatic.PostSurgeryDatasetID + ".png?" +
                        graphDapQuery + "\">&nbsp;&nbsp;&nbsp;&nbsp;\n");  //space between images if side-by-side
            } else {
                writer.write("<p><font class=\"subduedColor\">To view the map, check <tt>View : " + 
                    viewTitle[0] + "</tt> above.</font>\n");
            }

            writer.write(
                "  </td>\n" +
                "  <td width=\"50%\" valign=\"top\">\n"); 

            //1 = map = Detection Map
            writer.write(
                "<b>Detection Map</b>\n" +
                EDStatic.htmlTooltipImage(loggedInAs, viewTooltip[1]));
            if (viewChecked[1]) {  
                if (loggedInAs == null && smallTable.nRows() > 1) {
                    writer.write(
                        "<p><font class=\"subduedColor\">Since you aren't logged in, you can only see a Detection Map\n" +
                        "<br>if you have selected just one animal (above).</font>\n");
                } else if (allData) {
                    writer.write(
                        "<p><font class=\"subduedColor\">To view a Detection Map, you must select (above)\n" +
                        "<br>at least one non-\"" + ANY + "\" option.</font>\n");
                } else {
                    graphDapQuery = XML.encodeAsHTML(
                        "longitude,latitude,time" + newDapQuery.toString() + 
                        "&.draw=markers&.colorBar=|D||||");
                    writer.write(
                        "<br>(<a href=\"" + tErddapUrl + "/tabledap/" + EDStatic.PostDetectionDatasetID + ".graph?" +
                            graphDapQuery + "\">" +
                            "Refine the map and/or download the image</a>)\n" +
                        "<br><img width=\"" + EDStatic.imageWidths[1] + 
                            "\" height=\"" + EDStatic.imageHeights[1] + "\" " +
                            "alt=\"Detection locations and times.\" " +
                            "title=\"Detection locations and times.\" " +
                            "src=\"" + tErddapUrl + "/tabledap/" + EDStatic.PostDetectionDatasetID + ".png?" +
                            graphDapQuery + "\">\n");
                }
            } else {
                writer.write("<p><font class=\"subduedColor\">To view the map, check <tt>View : " + 
                    viewTitle[1] + "</tt> above.</font>\n" +
                    warn + "\n");
            }

            //end map table  
            writer.write(
                "  </td>\n" +
                "</tr>\n" +
                widgets.endTable()); 

            // 2 = viewRelatedDataCounts
            writer.write("\n" +
                "<br><a name=\"" + viewParam[2] + "\">&nbsp;</a><hr>\n" +
                "<p><b>" + viewTitle[2] + "</b>\n" +
                EDStatic.htmlTooltipImage(loggedInAs, viewTooltip[2]));
            if (viewChecked[2] && lastP >= 0) {  
                String lastPName = EDStatic.PostSubset[lastP];
                String fullCountsQuery = lastPName + countsQuery.toString();
                writer.write(
                    "&nbsp;&nbsp;\n" +
                    "(<a href=\"" + tErddapUrl + "/tabledap/" + EDStatic.PostSurgeryDatasetID + ".html?" +
                        XML.encodeAsHTML(fullCountsQuery + "&distinct()") + 
                        "\">Refine the data subset and/or download the data</a>)\n");

                try {                    
                    //get the raw data
                    PrimitiveArray varPA = (PrimitiveArray)(bigTable.findColumn(lastPName).clone());
                    Table countTable = new Table();
                    countTable.addColumn(lastPName, varPA);

                    //sort, count, remove duplicates
                    varPA.sortIgnoreCase();
                    int n = varPA.size();
                    IntArray countPA = new IntArray(n, false);
                    countTable.addColumn("Count", countPA);
                    int lastCount = 1;
                    countPA.add(lastCount);
                    BitSet keep = new BitSet(n);
                    keep.set(0, n); //initially, all set
                    for (int i = 1; i < n; i++) {
                        if (varPA.compare(i-1, i) == 0) {
                            keep.clear(i-1);
                            lastCount++;
                        } else {
                            lastCount = 1;
                        }
                        countPA.add(lastCount);
                    }
                    countTable.justKeep(keep);

                    //calculate percents
                    double stats[] = countPA.calculateStats();
                    double total = stats[PrimitiveArray.STATS_SUM];
                    n = countPA.size();
                    DoubleArray percentPA = new DoubleArray(n, false);
                    countTable.addColumn("Percent", percentPA);
                    for (int i = 0; i < n; i++) 
                        percentPA.add(Math2.roundTo(countPA.get(i) * 100 / total, 2));

                    //write results
                    String countsCon = countsConstraints.toString();
                    writer.write(
                    "<br>When " +
                        (countsCon.length() == 0? "there are no constraints, " : 
                            (XML.encodeAsHTML(countsCon) + (countsCon.length() < 40? ", " : ",\n<br>"))) +
                    "the counts of surgery data for the " + countTable.nRows() + 
                    " values of \"" + lastPName + "\" are:\n");
                    writer.flush(); //essential, since creating and using another writer to write the countTable
                    TableWriterHtmlTable.writeAllAndFinish(loggedInAs, countTable, 
                        new OutputStreamSourceSimple(out), 
                        false, "", false, "", "", 
                        true, false);          
                    writer.write("<p>The total of the counts is " + 
                        Math2.roundToLong(total) + ".\n");
                } catch (Throwable t) {
                    String message = MustBe.getShortErrorMessage(t);
                    String2.log("Caught:\n" + MustBe.throwableToString(t)); //log full message with stack trace
                    writer.write( 
                        //"<p>An error occurred while getting the data:\n" +
                        "<pre>" + XML.encodeAsPreHTML(message, 120) +
                        "</pre>\n");                        
                }

            } else {
                writer.write("<p><font class=\"subduedColor\">To view the surgery counts, check <tt>View : " + 
                    viewTitle[2] + "</tt> above and select a value for one of the variables above.</font>\n");
            }

            //3 = data = Surgery Data
            String newDapQueryString = newDapQuery.length() == 0?
                EDStatic.pEncode("&time>=&time<=") : 
                newDapQuery.toString();
            writer.write("<br><a name=\"" + viewParam[3] + "\">&nbsp;</a><hr>\n" +
                "<p><b>Surgery/Release Data</b>\n" +
                EDStatic.htmlTooltipImage(loggedInAs, viewTooltip[3]) +
                "&nbsp;&nbsp;(<a href=\"" + tErddapUrl + "/tabledap/" + 
                    EDStatic.PostSurgeryDatasetID + ".das\">Metadata</a>)\n" +
                "&nbsp;&nbsp;\n" +
                "(<a href=\"" + tErddapUrl + "/tabledap/" + EDStatic.PostSurgeryDatasetID + ".html?" +
                    XML.encodeAsHTML(newDapQueryString) + 
                    "\">Refine the data subset and/or download the data</a>)\n" +
                "<br>Note that the longitude, latitude, and time variables " +
                    "have data for the animal's release, not its surgery.\n");
            if (viewChecked[3]) {  
                if (allData) {
                    writer.write(
                        "\n<p><font class=\"subduedColor\">To view Surgery/Release Data, you must select (above)\n" +
                        "at least one non-\"" + ANY + "\" option.</font>\n");
                } else {
                    writer.flush(); //essential, since creating and using another writer to write the smallTable
                    TableWriterHtmlTable.writeAllAndFinish(loggedInAs, smallTable, 
                        new OutputStreamSourceSimple(out), 
                        false, "", false, "", "", 
                        true, true);          
                }
            } else {
                writer.write("<p><font class=\"subduedColor\">To view the data, check <tt>View : " + 
                    viewTitle[3] + "</tt> above.</font>\n");
            }

            //4 = data = Detection Data
            newDapQueryString = newDapQuery.toString();
            writer.write(
                "<br><a name=\"" + viewParam[4] + "\">&nbsp;</a><hr>\n" +
                "<p><b>Detection Data</b>\n" +
                EDStatic.htmlTooltipImage(loggedInAs, viewTooltip[4]) +
                "&nbsp;&nbsp;(<a href=\"" + tErddapUrl + "/tabledap/" + 
                    EDStatic.PostDetectionDatasetID + ".das\">Metadata</a>)\n" +
                "&nbsp;&nbsp;" +
                "(<a href=\"" + tErddapUrl + "/tabledap/" + EDStatic.PostDetectionDatasetID + ".html?" +
                    XML.encodeAsHTML(newDapQueryString) + 
                    "\">Refine the data subset and/or download the data</a>)\n" +
                "<br>Note that the first detection for each animal is from its release.\n");
            if (viewChecked[4]) {  
                if (loggedInAs == null && smallTable.nRows() > 1) {
                    writer.write(
                        "<p><font class=\"subduedColor\">Since you aren't logged in, \n" +
                        "you can only see Detection Data if you have selected just one animal (above).</font>\n");
                } else if (allData || newDapQuery.length() == 0) {
                    writer.write(
                        "<p><font class=\"subduedColor\">To view Detection Data, you must select (above)\n" +
                        "at least one non-\"" + ANY + "\" option.</font>\n");
                } else {
                    //generate htmlTable via getDataForDapQuery
                    writer.flush(); //essential, since creating and using another writer to write the detections
                    TableWriter tw = new TableWriterHtmlTable(loggedInAs, 
                        new OutputStreamSourceSimple(out),
                        false, "", false, // tWriteHeadAndBodyTags, tFileNameNoExt, tXhtmlMode,         
                        "", "", true, true); //tPreTableHtml, tPostTableHtml, tEncodeAsXML, tWriteUnits) 
                    String detectionNcUrl = "/tabledap/" + EDStatic.PostDetectionDatasetID + ".nc";
                    if (detectionEdd.handleViaSubsetVariables(loggedInAs, newDapQueryString, tw)) {}
                    else detectionEdd.getDataForDapQuery(loggedInAs, detectionNcUrl, newDapQueryString, tw);  
                }
            } else {
                writer.write("<p><font class=\"subduedColor\">To view the data, check <tt>View : " + 
                    viewTitle[4] + "</tt> above.</font>\n" +
                    warn + "\n");
            }


        } catch (Throwable t) {
            writer.write(EDStatic.htmlForException(t));
        }

        //end of document
        endHtmlWriter(out, writer, tErddapUrl, false);
    }

    /**
     * This indicates if the string 's' equals 'start' (e.g., "index") 
     * plus one of the plain file types.
     */
    protected static boolean endsWithPlainFileType(String s, String start) {
        for (int pft = 0; pft < plainFileTypes.length; pft++) { 
            if (s.equals(start + plainFileTypes[pft]))
                return true;
        }
        return false;
    }

    /**
     * Set the standard DAP header information. Call this before getting outputStream.
     *
     * @param response
     * @throws Throwable if trouble
     */
    public void standardDapHeader(HttpServletResponse response) throws Throwable {
        String rfc822date = Calendar2.getCurrentRFC822Zulu();
        response.setHeader("Date", rfc822date);             //DAP 2.0, 7.1.4.1
        response.setHeader("Last-Modified", rfc822date);    //DAP 2.0, 7.1.4.2   //this is not a good implementation
        //response.setHeader("Server", );                   //DAP 2.0, 7.1.4.3  optional
        response.setHeader("xdods-server", serverVersion);  //DAP 2.0, 7.1.7 (http header field names are case-insensitive)
        response.setHeader(EDStatic.programname + "-server", EDStatic.erddapVersion);  
    }

    
    /**
     * Get an outputStream for an html file
     *
     * @param request
     * @param response
     * @return an outputStream
     * @throws Throwable if trouble
     */
    public static OutputStream getHtmlOutputStream(HttpServletRequest request, HttpServletResponse response) 
        throws Throwable {

        OutputStreamSource outSource = new OutputStreamFromHttpResponse(
            request, response, "index", ".html", ".html");
        return outSource.outputStream("UTF-8");
    }

    /**
     * Get a writer for an html file and write up to and including the startHtmlBody
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param addToTitle   a string, not yet XML encoded
     * @param out
     * @return writer
     * @throws Throwable if trouble
     */
    Writer getHtmlWriter(String loggedInAs, String addToTitle, OutputStream out) throws Throwable {

        Writer writer = new OutputStreamWriter(out, "UTF-8");

        //write the information for this protocol (dataset list table and instructions)
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        writer.write(EDStatic.startHeadHtml(tErddapUrl, addToTitle));
        writer.write("\n</head>\n");
        writer.write(EDStatic.startBodyHtml(loggedInAs));
        writer.write("\n");
        writer.write(HtmlWidgets.htmlTooltipScript(EDStatic.imageDirUrl(loggedInAs)));
        writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better
        return writer;
    }

    /**
     * Write the end of the standard html doc to writer.
     *
     * @param out
     * @param writer
     * @param tErddapUrl  from EDStatic.erddapUrl(loggedInAs)  (erddapUrl, or erddapHttpsUrl if user is logged in)
     * @param forceWriteDiagnostics
     * @throws Throwable if trouble
     */
    void endHtmlWriter(OutputStream out, Writer writer, String tErddapUrl,
        boolean forceWriteDiagnostics) throws Throwable {


        //add the diagnostic info  
        if (EDStatic.displayDiagnosticInfo || forceWriteDiagnostics) 
            EDStatic.writeDiagnosticInfoHtml(writer);

        //end of document
        writer.write(EDStatic.endBodyHtml(tErddapUrl));
        writer.write("\n</html>\n");

        //essential
        writer.flush();
        if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
        out.close();         
    }


    /**
     * This writes the error (if not null or "") to the html writer.
     *
     * @param writer
     * @param request
     * @param error plain text, will be html-encoded here
     * @throws Throwable if trouble
     */
    void writeErrorHtml(Writer writer, HttpServletRequest request, String error) throws Throwable {
        if (error == null || error.length() == 0) 
            return;
        int colonPo = error.indexOf(": ");
        if (colonPo >= 0 && colonPo < error.length() - 5)
            error = error.substring(colonPo + 2);
        String query = SSR.percentDecode(request.getQueryString()); //percentDecode returns "" instead of null
        String requestUrl = request.getRequestURI();
        if (requestUrl == null) 
            requestUrl = "";
        if (requestUrl.startsWith("/"))
            requestUrl = requestUrl.substring(1);
        //encodeAsPreHTML(error) is essential -- to prevent Cross-site-scripting security vulnerability
        //(which allows hacker to insert his javascript into pages returned by server)
        //See Tomcat (Definitive Guide) pg 147
        error = XML.encodeAsPreHTML(error, 110);
        int brPo = error.indexOf("<br> at ");
        if (brPo < 0) 
            brPo = error.indexOf("<br>at ");
        if (brPo < 0) 
            brPo = error.length();
        writer.write(
            "<b><big>" + EDStatic.errorTitle + ":</big> " + error.substring(0, brPo) + "</b>" + 
                error.substring(brPo) + 
            "<br>&nbsp;");
        /* retired 2009-07-15
        writer.write(
            "<h2>" + EDStatic.errorTitle + "</h2>\n" +
            "<table border=\"0\" cellspacing=\"0\" cellpadding=\"0\">\n" +
            "<tr>\n" +
            "  <td nowrap>" + EDStatic.errorRequestUrl + "&nbsp;</td>\n" +
            //encodeAsHTML(query) is essential -- to prevent Cross-site-scripting security vulnerability
            //(which allows hacker to insert his javascript into pages returned by server)
            //See Tomcat (Definitive Guide) pg 147
            "  <td nowrap>" + EDStatic.baseUrl + "/" + XML.encodeAsHTML(requestUrl) + "</td>\n" +
            "</tr>\n" +
            "<tr>\n" +
            "  <td nowrap>" + EDStatic.errorRequestQuery + "&nbsp;</td>\n" +
            //encodeAsHTML(query) is essential -- to prevent Cross-site-scripting security vulnerability
            //(which allows hacker to insert his javascript into pages returned by server)
            //See Tomcat (Definitive Guide) pg 147
            "  <td nowrap>" + (query.length() == 0? "&nbsp;" : XML.encodeAsHTML(query)) + "</td>\n" +
            "</tr>\n" +
            "<tr>\n" +
            "  <td valign=\"top\" nowrap><b>" + EDStatic.errorTheError + "</b>&nbsp;</td>\n" +
            "  <td><b>" + error.substring(0, brPo) + "</b>" + 
                error.substring(brPo) + "</td>\n" + //not nowrap
            "</tr>\n" +
            "</table>\n" +
            "<br>&nbsp;");
        */
        /* older versions
        writer.write(
            "<p><b>There was an error in your request:</b>\n" +
            "<br>&nbsp; &nbsp;Your request URL: " + EDStatic.baseUrl + request.getRequestURI() + "\n" +
            "<br>&nbsp; &nbsp;Your request query: " + (query == null || userQuery.length() == 0? "" : query) + "\n" +
            "<br>&nbsp; &nbsp;The error: <b>" + error + "</b>\n");
        writer.write(
            "<p><b>Your request URL:</b> " + EDStatic.baseUrl + request.getRequestURI() + "\n" +
            "<p><b>Your request query:</b> " + (query == null || userQuery.length() == 0? "" : query) + "\n" +
            "<p><b>There was an error in your request:</b>\n" +
            "<br>" + error + "\n");
        */
    }


    /**
     * This is the first step in handling an exception/error.
     * If this returns true or throws Throwable, that is all that can be done: caller should call return.
     * If this returns false, the caller can/should handle the exception (response.isCommitted() is false);
     *
     * @returns false if response !isCommitted() and caller needs to handle the error 
     *   (e.g., send the desired type of error message)
     *   (this logs the error to String2.log).
     *   This currently doesn't return true.
     * @throw Throwable if response isCommitted(), t was rethrown.
     */
    public static boolean neededToSendErrorCode(HttpServletRequest request, 
        HttpServletResponse response, Throwable t) throws Throwable {
            
        if (response.isCommitted()) {
            //rethrow exception (will be handled in doGet try/catch)
            throw t;
        }

        //just log it
        String q = request.getQueryString();
        String message = ERROR + " for " + request.getRequestURI() +  
            (q != null && q.length() > 0? "?" + q : "") + //not decoded
            "\n" + MustBe.throwableToString(t); //log the details
        String2.log(message);
        return false;
    }

    /**
     * This calls response.sendError(500 INTERNAL_SERVER_ERROR, MustBe.throwableToString(t)).
     * Return after calling this.
     */
    public static void sendErrorCode(HttpServletRequest request, 
        HttpServletResponse response, Throwable t) throws ServletException {
        
        String tError = MustBe.getShortErrorMessage(t);

        try {

            //log the error            
            String q = request.getQueryString();
            String2.log(
                "*** sendErrorCode ERROR for " +
                    request.getRequestURI() + (q != null && q.length() > 0? "?" + q : "") + //not decoded
                "\nisCommitted=" + response.isCommitted() +
                "\n" + MustBe.throwableToString(t));  //always log full stack trace

            //if response isCommitted, nothing can be done
            if (response.isCommitted()) 
                return;

            //we can send the error code
            int errorNo = 
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            response.sendError(errorNo, tError); 
            return;

        } catch (Throwable t2) {
            //an exception occurs if response is committed
            throw new ServletException(t2);
        }
    }

    /**
     * This sends the HTTP resource NOT_FOUND error.
     * This always also sends the error to String2.log.
     *
     * @param message  use "" if nothing specific.
     *    The requestURI will always be pre-pended to the message.
     */
    public static void sendResourceNotFoundError(HttpServletRequest request, 
        HttpServletResponse response, String message) throws Throwable {

        try {
            message = (message == null || message.length() == 0)?
                request.getRequestURI() :
                request.getRequestURI() + " (" + message + ")";
            String2.log("Calling response.sendError(404 - SC_NOT_FOUND):\n" + message);
            response.sendError(HttpServletResponse.SC_NOT_FOUND, 
                "Resource not available: " + message);
        } catch (Throwable t) {
            throw new SimpleException("Resource not available: " + message);
        }
    }


    /** 
     * This gets the html for the search form.
     *
     * @param loggedInAs
     * @param pretext e.g., &lt;h2&gt;   Or use "" for none.
     * @param posttext e.g., &lt;/h2&gt;   Or use "" for none.
     * @param searchFor the default text to be searched for
     * @throws Throwable if trouble
     */
    public static String getSearchFormHtml(String loggedInAs,  
        String pretext, String posttext, String searchFor) throws Throwable {
     
        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        widgets.enterTextSubmitsForm = true;
        StringBuilder sb = new StringBuilder(
            widgets.beginForm("search", "GET", tErddapUrl + "/search/index.html", ""));
        sb.append(pretext + 
            "<a name=\"FullTextSearch\">" + EDStatic.searchFullTextHtml + "</a>" +
            posttext);
        if (searchFor == null)
            searchFor = "";
        widgets.htmlTooltips = false;
        sb.append(widgets.textField("searchFor", EDStatic.searchTip, 40, 255, searchFor, ""));
        widgets.htmlTooltips = true;
        sb.append(EDStatic.htmlTooltipImage(loggedInAs, EDStatic.searchHintsHtml(tErddapUrl)));
        widgets.htmlTooltips = false;
        sb.append(widgets.htmlButton("submit", null, null, EDStatic.searchClickTip, 
            EDStatic.searchButton, ""));
        widgets.htmlTooltips = true;
        //sb.append(EDStatic.searchHintsHtml(tErddapUrl));
        sb.append("\n");
        sb.append(widgets.endForm());        
        return sb.toString();
    }


    /** 
     * This returns a table with categorize options.
     *
     * @param tErddapUrl  from EDStatic.erddapUrl(loggedInAs)  (erddapUrl, or erddapHttpsUrl if user is logged in)
     * @param fileTypeName .html or a plainFileType e.g., .htmlTable
     * @return a table with categorize options.
     * @throws Throwable if trouble
     */
    public Table categorizeOptionsTable(String tErddapUrl, String fileTypeName) throws Throwable {

        Table table = new Table();
        StringArray csa = new StringArray();
        table.addColumn("Categorize", csa);
        if (fileTypeName.equals(".html")) {
            //1 column: links
            for (int cat = 0; cat < EDStatic.categoryAttributesInURLs.length; cat++) {
                String s = tErddapUrl + "/categorize/" + EDStatic.categoryAttributesInURLs[cat] + "/index.html";
                csa.add("<a href=\"" + s + "\">" + EDStatic.categoryAttributesInURLs[cat] + "</a>");
            }
        } else {
            //2 columns: categorize, url
            StringArray usa = new StringArray();
            table.addColumn("URL", usa);
            for (int cat = 0; cat < EDStatic.categoryAttributesInURLs.length; cat++) {
                csa.add(EDStatic.categoryAttributesInURLs[cat]);
                usa.add(tErddapUrl + "/categorize/" + EDStatic.categoryAttributesInURLs[cat] + "/index" + fileTypeName);
            }
        }
        return table;
    }

    /**
     * This writes a simple categorize options list (with &lt;br&gt;, for use on right-hand
     * side of getYouAreHereTable).
     *
     * @param tErddapUrl  from EDStatic.erddapUrl(loggedInAs)  (erddapUrl, or erddapHttpsUrl if user is logged in)
     * @return the html with the category links
     */
    public String getCategoryLinksHtml(String tErddapUrl) throws Throwable {
        
        Table catTable = categorizeOptionsTable(tErddapUrl, ".html");
        int cn = catTable.nRows();
        StringBuilder sb = new StringBuilder("Or, " + EDStatic.categoryTitleHtml + ":");
        int charCount = 0;
        for (int row = 0; row < cn; row++) {
            if (row % 4 == 0)
                sb.append("\n<br>");
            sb.append(catTable.getStringData(0, row) + 
                (row < cn - 1? ", \n" : "\n"));
        }
        return sb.toString();
    }

    /**
     * This writes the categorize options table
     *
     * @param loggedInAs
     * @param writer
     * @param attributeInURL e.g., institution   (it may be null or invalid)
     * @param homePage
     */
    public void writeCategorizeOptionsHtml1(String loggedInAs, Writer writer, 
        String attributeInURL, boolean homePage) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        if (homePage) {
            Table table = categorizeOptionsTable(tErddapUrl, ".html");
            int n = table.nRows();
            writer.write(
                "<h3><a name=\"SearchByCategory\">" + EDStatic.categoryTitleHtml + 
                "</a></h3>\n" +
                EDStatic.category1Html + "\n<br>(");
            for (int row = 0; row < n; row++) 
                writer.write(table.getStringData(0, row) + (row < n - 1? ", \n" : ""));
            writer.write(") " + EDStatic.category2Html + "\n" +
                EDStatic.category3Html + "\n");
            return;
        }

        //categorize page
        writer.write(
            //"<h3>" + EDStatic.categoryTitleHtml + "</h3>\n" +
            "<h3>1) Pick an attribute: &nbsp; " + 
            EDStatic.htmlTooltipImage(loggedInAs, EDStatic.category1Html + " " + EDStatic.category2Html) +
            "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" + //adds space between cat1 and cat2 
            "</h3>\n");
        String attsInURLs[] = EDStatic.categoryAttributesInURLs;
        HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
        writer.write(widgets.select("cat1", "", Math.min(attsInURLs.length, 12),
            attsInURLs, String2.indexOf(attsInURLs, attributeInURL), 
            "onchange=\"window.location='" + tErddapUrl + "/categorize/' + " +
                "this.options[this.selectedIndex].text + '/index.html';\""));
        writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better

        /* old style: html links   retired 2009-07-15
        //categorize page
        Table table = categorizeOptionsTable(tErddapUrl, ".html");
        int n = table.nRows();
        writer.write(
            //"<h2>" + EDStatic.categoryTitleHtml + "</h2>\n" +
            "<h3>1) Pick an attribute: &nbsp; " + 
            EDStatic.htmlTooltipImage(loggedInAs, EDStatic.category1Html + " " + EDStatic.category2Html) +
            "</h3>\n" +
            "<table class=\"erd commonBGColor\" cellspacing=\"0\">\n" +
            "  <tr>\n");
        for (int row = 0; row < n; row++) 
            writer.write("    <td>&nbsp;" + table.getStringData(0, row) + "&nbsp;</td>\n");
        writer.write(
            "  </tr>\n" +
            "</table>\n\n");
        writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better
        */
    }

    /** 
     * This writes the html with the category options to the writer (in a table with lots of columns).
     *
     * @param loggedInAs
     * @param writer
     * @param attribute must be valid  (e.g., ioos_category)
     * @param attributeInURL must be valid
     * @param value may be null or invalid (e.g., Location)
     * @throws Throwable if trouble
     */
    public void writeCategoryOptionsHtml2(String loggedInAs, Writer writer, 
        String attribute, String attributeInURL, String value) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String values[] = categoryInfo(attribute).toArray();
        String title = EDStatic.categorySearchHtml;
        title = String2.replaceAll(title, "&category;", attributeInURL);
        writer.write(
            "<h3>2) " + title + ": &nbsp; " +
            EDStatic.htmlTooltipImage(loggedInAs, EDStatic.categoryClickHtml) +
            "</h3>\n");
        if (values.length == 0) {
            writer.write(DataHelper.THERE_IS_NO_DATA);
        } else {
            HtmlWidgets widgets = new HtmlWidgets("", true, EDStatic.imageDirUrl(loggedInAs)); //true=htmlTooltips
            writer.write(widgets.select("cat2", "", Math.min(values.length, 12),
                values, String2.indexOf(values, value), 
                "onchange=\"window.location='" + tErddapUrl + "/categorize/" + attributeInURL + "/' + " +
                    "this.options[this.selectedIndex].text + '/index.html';\""));
        }
        writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better
    }

    /* *   old style: html links   retired 2009-07-15
     * This writes the html with the category options to the writer (in a table with lots of columns).
     *
     * @param loggedInAs
     * @param writer
     * @param categoryAttribute must be valid  (e.g., ioos_category)
     * @throws Throwable if trouble
     * /
    public void writeCategoryOptionsHtml2(loggedInAs, Writer writer, 
        String categoryAttribute) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        StringArray cats = categoryInfo(categoryAttribute);
        int nCats = cats.size();

        //find longest cat  and calculate nCols
        int max = Math.max(1, cats.maxStringLength());
        //table width never more than 150 chars.      e.g., 150/30 -> 5 cols;  150/31 -> 4 cols 
        int nCols = 150 / max; 
        nCols = Math.min(nCats, nCols); //never more cols than nCats
        nCols = Math.max(1, nCols);     //always at least 1 col
        //String2.log("  writeCategoryOptionsHtml max=" + max + " nCols=" + nCols);
        int nRows = Math2.hiDiv(nCats, nCols);

        //write the table
        String catTitle = EDStatic.categorySearchHtml;
        catTitle = String2.replaceAll(catTitle, "&category;", categoryAttribute);
        writer.write(
            "<h3>2) " + catTitle + ": &nbsp; " +
            EDStatic.htmlTooltipImage(loggedInAs, EDStatic.categoryClickHtml) +
            "</h3>\n");
        writer.write(
            "<table class=\"erd commonBGColor\" cellspacing=\"0\">\n"); 
            //"<table border=\"1\" class==\"commonBGColor\"" + 
            //" cellspacing=\"0\" cellpadding=\"2\">\n");

        //organized to be read top to bottom, then left to right
        //interesting case: nCats=7, nCols=6
        //   so nRows=2, then only need nCols=4; so modify nCols   
        nCols = Math2.hiDiv(nCats, Math.max(1, nRows));
        for (int row = 0; row < nRows; row++) {
            writer.write("  <tr>\n");
            for (int col = 0; col < nCols; col++) {
                writer.write("    <td nowrap>");
                int i = col * nRows + row;
                if (i < nCats) { 
                    String tc = cats.get(i); //e.g., Temperature
                    writer.write(
                        "&nbsp;<a href=\"" + tErddapUrl + "/categorize/" + categoryAttribute + "/" + 
                        tc + "/index.html\">" + tc + "</a>&nbsp;");
                } else {
                    writer.write("&nbsp;");
                }
                writer.write("</td>\n");
            }
            writer.write("  </tr>\n");
        }

        writer.write("</table>\n");
        writer.flush(); //Steve Souder says: the sooner you can send some html to user, the better
    } */

    /** 
     * This sens a response: a table with two columns (Category, URL).
     *
     * @param request
     * @param response
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param attribute must be valid  (e.g., ioos_category)
     * @param attributeInURL must be valid  (e.g., ioos_category)
     * @param fileTypeName a plainFileType, e.g., .htmlTable
     * @throws Throwable if trouble
     */
    public void sendCategoryPftOptionsTable(HttpServletRequest request, 
        HttpServletResponse response, String loggedInAs, String attribute, 
        String attributeInURL, String fileTypeName) throws Throwable {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        StringArray cats = categoryInfo(attribute); //already safe
        int nCats = cats.size();

        //make the table
        Table table = new Table();
        StringArray catCol = new StringArray();
        StringArray urlCol = new StringArray();
        table.addColumn("Category", catCol);
        table.addColumn("URL", urlCol);
        for (int i = 0; i < nCats; i++) {
            String cat = cats.get(i); //e.g., Temperature    already safe
            catCol.add(cat);
            urlCol.add(tErddapUrl + "/categorize/" + attributeInURL + "/" + cat + "/index" + fileTypeName); 
        }

        //send it  
        sendPlainTable(loggedInAs, request, response, table, attributeInURL, fileTypeName);
    }


    /**
     * Given a list of datasetIDs, this makes a sorted table of the datasets info.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDs the id's of the datasets (e.g., "pmelTao") that should be put into the table
     * @param sortByTitle if true, rows will be sorted by title.
     *    If false, they are left in order of datasetIDs.
     * @param fileTypeName the file type name (e.g., ".htmlTable") to use for info links
     * @return table a table with plain text information about the datasets
     */
    public Table makePlainDatasetTable(String loggedInAs, 
        StringArray datasetIDs, boolean sortByTitle, String fileTypeName) {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String roles[] = EDStatic.getRoles(loggedInAs);
        boolean isLoggedIn = loggedInAs != null;
        Table table = new Table();
        StringArray gdCol = new StringArray();
        StringArray subCol = new StringArray();
        StringArray tdCol = new StringArray();
        StringArray magCol = new StringArray();
        StringArray sosCol = new StringArray();
        StringArray wcsCol = new StringArray();
        StringArray wmsCol = new StringArray();
        StringArray accessCol = new StringArray();
        StringArray titleCol = new StringArray();
        StringArray summaryCol = new StringArray();
        StringArray infoCol = new StringArray();
        StringArray backgroundCol = new StringArray();
        StringArray rssCol = new StringArray();
        StringArray emailCol = new StringArray();
        StringArray institutionCol = new StringArray();
        StringArray idCol = new StringArray();  //useful for java programs
        table.addColumn("griddap", gdCol);  //just protocol name
        table.addColumn("Subset", subCol);
        table.addColumn("tabledap", tdCol);
        table.addColumn("Make A Graph", magCol);
        if (EDStatic.sosActive) table.addColumn("sos", sosCol);
        if (EDStatic.wcsActive) table.addColumn("wcs", wcsCol);
        table.addColumn("wms", wmsCol);
        if (EDStatic.authentication.length() > 0)
            table.addColumn("Accessible", accessCol);
        int sortOn = table.addColumn("Title", titleCol);
        table.addColumn("Summary", summaryCol);
        table.addColumn("Info", infoCol);
        table.addColumn("Background Info", backgroundCol);
        table.addColumn("RSS", rssCol);
        table.addColumn("Email", emailCol);
        table.addColumn("Institution", institutionCol);
        table.addColumn("Dataset ID", idCol);
        for (int i = 0; i < datasetIDs.size(); i++) {
            String tId = datasetIDs.get(i);
            EDD edd = (EDD)gridDatasetHashMap.get(tId);
            if (edd == null) 
                edd = (EDD)tableDatasetHashMap.get(tId);
            if (edd == null) //perhaps just deleted
                continue;
            boolean isAccessible = edd.isAccessibleTo(roles);
            if (!EDStatic.listPrivateDatasets && !isAccessible)
                continue;

            String daps = tErddapUrl + "/" + edd.dapProtocol() + "/" + tId; //without an extension, so easy to add
            gdCol.add(edd instanceof EDDGrid? daps : "");
            subCol.add(edd.accessibleViaSubset().length() == 0? 
                daps + ".subset" : "");
            tdCol.add(edd instanceof EDDTable? daps : "");
            magCol.add(edd.accessibleViaMAG().length() == 0? 
                daps + ".graph" : "");
            sosCol.add(edd.accessibleViaSOS().length() == 0? 
                tErddapUrl + "/sos/" + tId + "/" + EDDTable.sosServer : "");
            wcsCol.add(edd.accessibleViaWCS().length() == 0? 
                tErddapUrl + "/wcs/" + tId + "/" + EDDGrid.wcsServer : "");
            wmsCol.add(edd.accessibleViaWMS().length() == 0? 
                tErddapUrl + "/wms/" + tId + "/" + WMS_SERVER : "");
            accessCol.add(edd.getAccessibleTo() == null? "public" :
                !isLoggedIn? "log in" :
                isAccessible? "yes" : "no");
            titleCol.add(edd.title());
            summaryCol.add(edd.extendedSummary());
            infoCol.add(tErddapUrl + "/info/" + edd.datasetID() + "/index" + fileTypeName);
            backgroundCol.add(edd.infoUrl());
            rssCol.add(EDStatic.erddapUrl + "/rss/" + edd.datasetID()+ ".rss"); //never https url
            emailCol.add(EDStatic.subscriptionSystemActive?
                tErddapUrl + "/" + Subscriptions.ADD_HTML + 
                    "?datasetID=" + edd.datasetID()+ "&showErrors=false&email=" : 
                "");
            institutionCol.add(edd.institution());
            idCol.add(tId);
        }
        if (sortByTitle)
            table.sortIgnoreCase(new int[]{sortOn}, new boolean[]{true});
        return table;
    }

    /**
     * Given a list of datasetIDs, this makes a sorted table of the datasets info.
     *
     * @param loggedInAs  the name of the logged in user (or null if not logged in)
     * @param datasetIDs the id's of the datasets (e.g., "pmelTao") that should be put into the table
     * @param sortByTitle if true, rows will be sorted by title.
     *    If false, they are left in order of datasetIDs.
     * @return table a table with html-formatted information about the datasets
     */
    public Table makeHtmlDatasetTable(String loggedInAs,
        StringArray datasetIDs, boolean sortByTitle) {

        String tErddapUrl = EDStatic.erddapUrl(loggedInAs);
        String roles[] = EDStatic.getRoles(loggedInAs);
        boolean isLoggedIn = loggedInAs != null;
        Table table = new Table();
        StringArray gdCol = new StringArray();
        StringArray subCol = new StringArray();
        StringArray tdCol = new StringArray();
        StringArray magCol = new StringArray();
        StringArray sosCol = new StringArray();
        StringArray wcsCol = new StringArray();
        StringArray wmsCol = new StringArray();
        StringArray accessCol = new StringArray();
        StringArray plainTitleCol = new StringArray(); //for sorting
        StringArray titleCol = new StringArray();
        StringArray summaryCol = new StringArray();
        StringArray infoCol = new StringArray();
        StringArray backgroundCol = new StringArray();
        StringArray rssCol = new StringArray();  
        StringArray emailCol = new StringArray(); 
        StringArray institutionCol = new StringArray();
        StringArray idCol = new StringArray();  //useful for java programs
        table.addColumn("Grid<br>DAP<br>Data", gdCol);
        table.addColumn("Sub-<br>set", subCol);
        table.addColumn("Table<br>DAP<br>Data", tdCol);
        table.addColumn("Make<br>A<br>Graph", magCol);
        if (EDStatic.sosActive) table.addColumn("S<br>O<br>S", sosCol);
        if (EDStatic.wcsActive) table.addColumn("W<br>C<br>S", wcsCol);
        table.addColumn("W<br>M<br>S", wmsCol);
        String accessTip = EDStatic.dtAccessible;
        if (isLoggedIn)
            accessTip += EDStatic.dtAccessibleYes;
        if (EDStatic.authentication.length() > 0 && EDStatic.listPrivateDatasets) //this erddap supports logging in
            accessTip += isLoggedIn?
                EDStatic.dtAccessibleNo :
                EDStatic.dtAccessibleLogIn;
        if (EDStatic.authentication.length() > 0)
            table.addColumn("Acces-<br>sible<br>" + EDStatic.htmlTooltipImage(loggedInAs, accessTip),
                accessCol);
        String loginHref = EDStatic.authentication.length() == 0? "no" :
            "<a href=\"" + EDStatic.erddapHttpsUrl + "/login.html\" " +
            "title=\"" + EDStatic.dtLogIn + "\">log in</a>";
        table.addColumn("Title", titleCol);
        int sortOn = table.addColumn("Plain Title", plainTitleCol); 
        table.addColumn("Sum-<br>mary", summaryCol);
        table.addColumn("Meta-<br>data<br>Info", infoCol);
        table.addColumn("Back-<br>ground<br>Info", backgroundCol);
        table.addColumn("RSS", rssCol);
        table.addColumn("E<br>mail", emailCol);
        table.addColumn("Institution", institutionCol);
        table.addColumn("Dataset ID", idCol);
        for (int i = 0; i < datasetIDs.size(); i++) {
            String tId = datasetIDs.get(i);
            EDD edd = (EDD)gridDatasetHashMap.get(tId);
            if (edd == null)
                edd = (EDD)tableDatasetHashMap.get(tId);
            if (edd == null)  //if just deleted
                continue; 
            boolean isAccessible = edd.isAccessibleTo(roles);
            if (!EDStatic.listPrivateDatasets && !isAccessible)
                continue;

            String daps = "&nbsp;<a href=\"" + tErddapUrl + "/" + edd.dapProtocol() + 
                "/" + tId + ".html\" " +
                "title=\"" + EDStatic.dtDAF1 + " " + edd.dapProtocol() + " " + EDStatic.dtDAF2 + "\" " +
                ">data</a>&nbsp;"; 
            gdCol.add(edd instanceof EDDGrid?  daps : "&nbsp;"); 
            subCol.add(edd.accessibleViaSubset().length() == 0? 
                " &nbsp;<a href=\"" + tErddapUrl + "/tabledap/" + tId + ".subset\" " +
                    "title=\"" + EDStatic.dtSubset + "\" " +
                    ">set</a>" : 
                "&nbsp;");
            tdCol.add(edd instanceof EDDTable? daps : "&nbsp;");
            magCol.add(edd.accessibleViaMAG().length() == 0? 
                " &nbsp;<a href=\"" + tErddapUrl + "/" + edd.dapProtocol() + 
                    "/" + tId + ".graph\" " +
                    "title=\"" + EDStatic.dtMAG + "\" " +
                    ">graph</a>" : 
                "&nbsp;");
            sosCol.add(edd.accessibleViaSOS().length() == 0? 
                "&nbsp;<a href=\"" + tErddapUrl + "/sos/" + tId + "/index.html\" " +
                    "title=\"" + EDStatic.dtSOS + "\" >" +
                    "S</a>&nbsp;" : 
                "&nbsp;");
            wcsCol.add(edd.accessibleViaWCS().length() == 0? 
                "&nbsp;<a href=\"" + tErddapUrl + "/wcs/" + tId + "/index.html\" " +
                    "title=\"" + EDStatic.dtWCS + "\" >" +
                    "C</a>&nbsp;" : 
                "&nbsp;");
            wmsCol.add(edd.accessibleViaWMS().length() == 0? 
                "&nbsp;<a href=\"" + tErddapUrl + "/wms/" + tId + "/index.html\" " +
                    "title=\"" + EDStatic.dtWMS + "\" >" +
                    "M</a>&nbsp;" : 
                "&nbsp;");
            accessCol.add(edd.getAccessibleTo() == null? "public" :
                !isLoggedIn? loginHref :
                isAccessible? "yes" : "no");
            String tTitle = edd.title();
            plainTitleCol.add(tTitle);
            if (tTitle.length() > 95) 
                titleCol.add(
                    "<table style=\"border:0px;\" width=\"100%\" cellspacing=\"0\" cellpadding=\"2\">\n" +
                    "<tr>\n" +
                    //45 + 45 + 5 (for " ... ") = 95
                    "  <td nowrap style=\"border:0px; padding:0px\" >" + 
                        XML.encodeAsHTML(tTitle.substring(0, 45)) + " ...&nbsp;</td>\n" +
                    //length of [time][depth][latitude][longitude] is 34, some are longer
                    "  <td nowrap style=\"border:0px; padding:0px\" align=\"right\">" + 
                        XML.encodeAsHTML(tTitle.substring(tTitle.length() - 45)) + " " + 
                        EDStatic.htmlTooltipImage(loggedInAs, XML.encodeAsPreHTML(tTitle, 100)) +
                        "</td>\n" +
                    "</tr>\n" +
                    "</table>\n");
            else titleCol.add(XML.encodeAsHTML(tTitle));
            summaryCol.add("&nbsp;&nbsp;&nbsp;" + EDStatic.htmlTooltipImage(loggedInAs, 
                XML.encodeAsPreHTML(edd.extendedSummary(), 100)));
            infoCol.add("&nbsp;<a href=\"" + tErddapUrl + "/info/" + edd.datasetID() + 
                "/index.html\" " + //here, always .html
                "title=\"" + EDStatic.clickInfo + "\" >meta</a>");
            backgroundCol.add("<a href=\"" + XML.encodeAsHTML(edd.infoUrl()) + "\" " +
                "title=\"" + EDStatic.clickBackgroundInfo + "\" >background</a>");
            rssCol.add(edd.rssHref(loggedInAs));
            emailCol.add("&nbsp;" + edd.emailHref(loggedInAs) + "&nbsp;");
            String tInstitution = edd.institution();
            if (tInstitution.length() > 20) 
                institutionCol.add(
                    "<table style=\"border:0px;\" width=\"100%\" cellspacing=\"0\" cellpadding=\"2\">\n" +
                    "<tr>\n" +
                    "  <td nowrap style=\"border:0px; padding:0px\" >" + 
                        XML.encodeAsHTML(tInstitution.substring(0, 15)) + "</td>\n" +
                    "  <td nowrap style=\"border:0px; padding:0px\" align=\"right\">" + 
                        "&nbsp;... " +
                        EDStatic.htmlTooltipImage(loggedInAs, XML.encodeAsPreHTML(tInstitution, 100)) +
                        "</td>\n" +
                    "</tr>\n" +
                    "</table>\n");
                    //XML.encodeAsHTML(tInstitution.substring(0, 15)) + " ... " +
                    //EDStatic.htmlTooltipImage(loggedInAs, 
                    //    XML.encodeAsPreHTML(tInstitution, 100)));
            else institutionCol.add(XML.encodeAsHTML(tInstitution));
            idCol.add(tId);
        }
        if (sortByTitle) 
            table.sortIgnoreCase(new int[]{sortOn}, new boolean[]{true});
        table.removeColumn(sortOn); //in any case, remove the plainTitle column
        return table;
    }

    /**
     * This writes the plain (non-html) table as a plainFileType response.
     *
     * @param fileName e.g., Time
     * @param fileTypeName e.g., .htmlTable
     */
    void sendPlainTable(String loggedInAs, HttpServletRequest request, HttpServletResponse response, 
        Table table, String fileName, String fileTypeName) throws Throwable {

        int po = String2.indexOf(EDDTable.dataFileTypeNames, fileTypeName);
        String fileTypeExtension = EDDTable.dataFileTypeExtensions[po];

        OutputStreamSource outSource = new OutputStreamFromHttpResponse(
            request, response, fileName, fileTypeName, fileTypeExtension); 

        if (fileTypeName.equals(".htmlTable")) {
            TableWriterHtmlTable.writeAllAndFinish(loggedInAs, table, outSource, 
                true, fileName, false,
                "", "", true, false); //pre, post, encodeAsHTML, writeUnits

        } else if (fileTypeName.equals(".json")) {
            //did query include &.jsonp= ?
            String parts[] = EDD.getUserQueryParts(request.getQueryString());
            String jsonp = String2.stringStartsWith(parts, ".jsonp="); //may be null
            if (jsonp != null) 
                jsonp = SSR.percentDecode(jsonp.substring(7));

            TableWriterJson.writeAllAndFinish(table, outSource, jsonp, false); //writeUnits

        } else if (fileTypeName.equals(".csv")) {
            TableWriterSeparatedValue.writeAllAndFinish(table, outSource,
                ", ", true, '0', "NaN"); //separator, quoted, writeUnits

        } else if (fileTypeName.equals(".mat")) {
            //avoid troublesome var names (e.g., with spaces)
            int nColumns = table.nColumns();
            for (int col = 0; col < nColumns; col++) 
                table.setColumnName(col, 
                    String2.modifyToBeFileNameSafe(table.getColumnName(col)));

            //??? use goofy standard structure name (nice that it's always the same);
            //  could use fileName but often long
            table.saveAsMatlab(outSource.outputStream(""), "response");  

        } else if (fileTypeName.equals(".nc")) {
            //avoid troublesome var names (e.g., with spaces)
            int nColumns = table.nColumns();
            for (int col = 0; col < nColumns; col++) 
                table.setColumnName(col, 
                    String2.modifyToBeFileNameSafe(table.getColumnName(col)));

            //This is different from other formats (which stream the results to the user),
            //since a file is being created then sent.
            //Append a random# to fileName to deal with different responses 
            //for almost simultaneous requests
            //(e.g., all Advanced Search requests have fileName=AdvancedSearch)
            String ncFileName = fileName + "_" + Math2.random(Integer.MAX_VALUE) + ".nc";
            table.saveAsFlatNc(EDStatic.fullPlainFileNcCacheDirectory + ncFileName, 
                "row", false); //convertToFakeMissingValues          
            doTransfer(request, response, EDStatic.fullPlainFileNcCacheDirectory, 
                "_plainFileNc/", //dir that appears to users (but it doesn't normally)
                ncFileName, outSource.outputStream("")); 
            //if simpleDelete fails, cache cleaning will delete it later
            File2.simpleDelete(EDStatic.fullPlainFileNcCacheDirectory + ncFileName); 

        } else if (fileTypeName.equals(".tsv")) {
            TableWriterSeparatedValue.writeAllAndFinish(table, outSource, 
                "\t", false, '0', "NaN"); //separator, quoted, writeUnits

        } else if (fileTypeName.equals(".xhtml")) {
            TableWriterHtmlTable.writeAllAndFinish(loggedInAs, table, outSource, 
                true, fileName, true,
                "", "", true, false); //pre, post, encodeAsHTML, writeUnits

        } else {
            throw new SimpleException("Unsupported fileType=" + fileTypeName + " at the end of the requestUrl."); 
        }

        //essential
        OutputStream out = outSource.outputStream(""); 
        if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
        out.close(); 

    }

    /** THIS IS NO LONGER ACTIVE. USE sendErrorCode() INSTEAD.
     * This sends a plain error message. 
     * 
     */
    /*void sendPlainError(HttpServletRequest request, HttpServletResponse response, 
        String fileTypeName, String error) throws Throwable {

        if (fileTypeName.equals(".json")) {
            OutputStream out = (new OutputStreamFromHttpResponse(request, response, ERROR, ".json", ".json")).outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");
            writer.write(
                "{\n" +
                "  \"" + ERROR + "\": " + String2.toJson(error) + "\n" +
                "}\n");

            //essential
            writer.flush();
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 
            return;
        }

        if (fileTypeName.equals(".csv") ||
            fileTypeName.equals(".tsv") ||
            fileTypeName.equals(".htmlTable") ||
//better error format for .htmlTable and .xhtml???
            fileTypeName.equals(".xhtml")) {  

            OutputStream out = (new OutputStreamFromHttpResponse(request, response, ERROR, ".txt", ".txt")).outputStream("UTF-8");
            Writer writer = new OutputStreamWriter(out, "UTF-8");
            if (!error.startsWith(ERROR))
                writer.write(ERROR + ": ");
            writer.write(error + "\n");

            //essential
            writer.flush();
            if (out instanceof ZipOutputStream) ((ZipOutputStream)out).closeEntry();
            out.close(); 
            return;
        }

        throw new SimpleException("Unsupported fileType=" + fileTypeName + " at the end of the requestUrl."); 
    }*/

    /**
     * This makes a erddapContent.zip file with the [tomcat]/content/erddap files for distribution.
     *
     * @param removeDir e.g., "c:/programs/tomcat/samples/"     
     * @param destinationDir  e.g., "c:/backup/"
     */
    public static void makeErddapContentZip(String removeDir, String destinationDir) throws Throwable {
        String2.log("*** makeErddapContentZip dir=" + destinationDir);
        String baseDir = removeDir + "content/erddap/";
        SSR.zip(destinationDir + "erddapContent.zip", 
            new String[]{
                baseDir + "datasets.xml",
                baseDir + "messages.xml",
                baseDir + "setup.xml",
                baseDir + "images/erddapStart.css",
                baseDir + "images/erddapAlt.css"},
            10, removeDir);
    }

    /**
     * This is an attempt to assist Tomcat/Java in shutting down erddap.
     * Tomcat/Java will call this; no one else should.
     * Java calls this when an object is no longer used, just before garbage collection. 
     * 
     */
    protected void finalize() throws Throwable {
        try {  //extra assistance/insurance
            EDStatic.destroy();   //but Tomcat should call ERDDAP.destroy, which calls EDStatic.destroy().
        } catch (Throwable t) {
        }
        super.finalize();
    }

    /**
     * This is used by Bob to do simple tests of the basic Erddap services 
     * from the ERDDAP at EDStatic.erddapUrl. It assumes Bob's test datasets are available.
     *
     */
    public static void testBasic() throws Throwable {
        Erddap.verbose = true;
        Erddap.reallyVerbose = true;
        EDD.testVerboseOn();
        String results, expected;
        String2.log("\n*** Erddap.test");
        int po;

        try {
            //home page
            results = SSR.getUrlResponseString(EDStatic.erddapUrl); //redirects to index.html
            expected = "The small effort to set up ERDDAP brings many benefits.";
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/"); //redirects to index.html
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/index.html"); 
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);

            
            //test version info  (opendap spec section 7.2.5)
            //"version" instead of datasetID
            expected = 
                "Core Version: DAP/2.0\n" +
                "Server Version: dods/3.7\n" +
                "ERDDAP_version: 1.36\n";
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/version");
            Test.ensureEqual(results, expected, "results=\n" + results);
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/version");
            Test.ensureEqual(results, expected, "results=\n" + results);

            //"version.txt"
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/version.txt");
            Test.ensureEqual(results, expected, "results=\n" + results);
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/version.txt");
            Test.ensureEqual(results, expected, "results=\n" + results);

            //".ver"
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/etopo180.ver");
            Test.ensureEqual(results, expected, "results=\n" + results);
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/erdGlobecBottle.ver");
            Test.ensureEqual(results, expected, "results=\n" + results);


            //help
            expected = "griddap to Request Data and Graphs from Gridded Datasets";
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/help"); 
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/documentation.html"); 
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/erdMHchla8day.help"); 
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);


            expected = "tabledap to Request Data and Graphs from Tabular Datasets";
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/help"); 
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/documentation.html"); 
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/erdGlobecBottle.help"); 
            Test.ensureTrue(results.indexOf(expected) >= 0, "results=\n" + results);

            //error 404
            results = "";
            try {
                SSR.getUrlResponseString(EDStatic.erddapUrl + "/gibberish"); 
            } catch (Throwable t) {
                results = t.toString();
            }
            Test.ensureTrue(results.indexOf("java.io.FileNotFoundException") >= 0, "results=\n" + results);

            //info    list all datasets
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/info/index.html"); 
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("GLOBEC NEP Rosette Bottle Data (2002)") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("SST, Blended, Global, EXPERIMENTAL (5 Day Composite)") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/info/index.csv"); 
            Test.ensureTrue(results.indexOf("</html>") < 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("GLOBEC NEP Rosette Bottle Data (2002)") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("SST, Blended, Global, EXPERIMENTAL (5 Day Composite)") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/info/erdGlobecBottle/index.html"); 
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("ioos_category") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Location") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("long_name") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Cast Number") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/info/erdGlobecBottle/index.tsv"); 
            Test.ensureTrue(results.indexOf("\t") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("ioos_category") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Location") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("long_name") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Cast Number") >= 0, "results=\n" + results);


            //search    
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/search/index.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Do a Full Text Search for Datasets") >= 0, "results=\n" + results);
            //index.otherFileType must have ?searchFor=...

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/search/index.html?searchFor=all");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">Title\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">RSS\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                ">Chlorophyll-a, Aqua MODIS, NPP, Global, Science Quality (8 Day Composite)\n") >= 0,
                "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                ">GLOBEC NEP Rosette Bottle Data (2002)") >= 0,
                "results=\n" + results);            
           
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/search/index.htmlTable?searchFor=all");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">Title\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">RSS\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                ">Chlorophyll-a, Aqua MODIS, NPP, Global, Science Quality (8 Day Composite)\n") >= 0,
                "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                ">GLOBEC NEP Rosette Bottle Data (2002)\n") >= 0,
                "results=\n" + results);            
           
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/search/index.html?searchFor=tao+pmel");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">TAO/TRITON, RAMA, and PIRATA Buoys, Daily, Sea Surface Temperature\n") > 0,
                "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/search/index.tsv?searchFor=tao+pmel");
            Test.ensureTrue(results.indexOf("\tTAO/TRITON, RAMA, and PIRATA Buoys, Daily, Sea Surface Temperature\t") > 0,
                "results=\n" + results);


            //categorize
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/index.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                ">standard_name\n") >= 0,
                "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/index.json");
            Test.ensureEqual(results, 
"{\n" +
"  \"table\": {\n" +
"    \"columnNames\": [\"Categorize\", \"URL\"],\n" +
"    \"columnTypes\": [\"String\", \"String\"],\n" +
"    \"rows\": [\n" +
"      [\"cdm_data_type\", \"http://127.0.0.1:8080/cwexperimental/categorize/cdm_data_type/index.json\"],\n" +
"      [\"dataVariables\", \"http://127.0.0.1:8080/cwexperimental/categorize/dataVariables/index.json\"],\n" +
"      [\"institution\", \"http://127.0.0.1:8080/cwexperimental/categorize/institution/index.json\"],\n" +
"      [\"ioos_category\", \"http://127.0.0.1:8080/cwexperimental/categorize/ioos_category/index.json\"],\n" +
"      [\"long_name\", \"http://127.0.0.1:8080/cwexperimental/categorize/long_name/index.json\"],\n" +
"      [\"parameterCode\", \"http://127.0.0.1:8080/cwexperimental/categorize/parameterCode/index.json\"],\n" +
"      [\"parameterName\", \"http://127.0.0.1:8080/cwexperimental/categorize/parameterName/index.json\"],\n" +
"      [\"statisticCode\", \"http://127.0.0.1:8080/cwexperimental/categorize/statisticCode/index.json\"],\n" +
"      [\"statisticName\", \"http://127.0.0.1:8080/cwexperimental/categorize/statisticName/index.json\"],\n" +
"      [\"standard_name\", \"http://127.0.0.1:8080/cwexperimental/categorize/standard_name/index.json\"]\n" +
"    ]\n" +
"  }\n" +
"}\n", 
                "results=\n" + results);

            //json with jsonp 
            String jsonp = "Some encoded {}\n() ! text";
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/index.json?.jsonp=" + SSR.percentEncode(jsonp));
            Test.ensureEqual(results, 
jsonp + "(" +
"{\n" +
"  \"table\": {\n" +
"    \"columnNames\": [\"Categorize\", \"URL\"],\n" +
"    \"columnTypes\": [\"String\", \"String\"],\n" +
"    \"rows\": [\n" +
"      [\"cdm_data_type\", \"http://127.0.0.1:8080/cwexperimental/categorize/cdm_data_type/index.json\"],\n" +
"      [\"dataVariables\", \"http://127.0.0.1:8080/cwexperimental/categorize/dataVariables/index.json\"],\n" +
"      [\"institution\", \"http://127.0.0.1:8080/cwexperimental/categorize/institution/index.json\"],\n" +
"      [\"ioos_category\", \"http://127.0.0.1:8080/cwexperimental/categorize/ioos_category/index.json\"],\n" +
"      [\"long_name\", \"http://127.0.0.1:8080/cwexperimental/categorize/long_name/index.json\"],\n" +
"      [\"parameterCode\", \"http://127.0.0.1:8080/cwexperimental/categorize/parameterCode/index.json\"],\n" +
"      [\"parameterName\", \"http://127.0.0.1:8080/cwexperimental/categorize/parameterName/index.json\"],\n" +
"      [\"statisticCode\", \"http://127.0.0.1:8080/cwexperimental/categorize/statisticCode/index.json\"],\n" +
"      [\"statisticName\", \"http://127.0.0.1:8080/cwexperimental/categorize/statisticName/index.json\"],\n" +
"      [\"standard_name\", \"http://127.0.0.1:8080/cwexperimental/categorize/standard_name/index.json\"]\n" +
"    ]\n" +
"  }\n" +
"}\n" +
")", 
                "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/standard_name/index.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">sea_water_temperature\n") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/standard_name/index.json");
            Test.ensureTrue(results.indexOf("\"table\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"sea_water_temperature\"") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/institution/index.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">ioos_category\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">noaa_coastwatch_west_coast_node\n") >= 0, 
                "results=\n" + results);
            Test.ensureTrue(results.indexOf(">noaa_pmel\n") >= 0, "results=\n" + results);
            
            results = String2.annotatedString(SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/institution/index.tsv"));
            Test.ensureTrue(results.indexOf("Category[9]URL[10]") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                "noaa_coastwatch_west_coast_node[9]http://127.0.0.1:8080/cwexperimental/categorize/institution/noaa_coastwatch_west_coast_node/index.tsv[10]") >= 0, 
                "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                "noaa_pmel[9]http://127.0.0.1:8080/cwexperimental/categorize/institution/noaa_pmel/index.tsv[10]") >= 0, 
                "results=\n" + results);
            
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/standard_name/sea_water_temperature/index.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                ">erdGlobecBottle\n") >= 0,
                "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/categorize/standard_name/sea_water_temperature/index.json");
            expected = 
"{\n" +
"  \"table\": {\n" +
"    \"columnNames\": [\"griddap\", \"Subset\", \"tabledap\", \"Make A Graph\", " +
                (EDStatic.sosActive? "\"sos\", " : "") +
                (EDStatic.wcsActive? "\"wcs\", " : "") +
                "\"wms\", \"Title\", \"Summary\", \"Info\", \"Background Info\", \"RSS\", \"Email\", \"Institution\", \"Dataset ID\"],\n" +
"    \"columnTypes\": [\"String\", \"String\", \"String\", \"String\", " +
                (EDStatic.sosActive? "\"String\", " : "") +
                (EDStatic.wcsActive? "\"String\", " : "") +
                "\"String\", \"String\", \"String\", \"String\", \"String\", \"String\", \"String\", \"String\", \"String\"],\n" +
"    \"rows\": [\n";
            Test.ensureEqual(results.substring(0, expected.length()), expected, "results=\n" + results);

            expected =            
"http://127.0.0.1:8080/cwexperimental/tabledap/erdGlobecBottle.subset\", " +                
"\"http://127.0.0.1:8080/cwexperimental/tabledap/erdGlobecBottle\", " +
"\"http://127.0.0.1:8080/cwexperimental/tabledap/erdGlobecBottle.graph\", " + 
                (EDStatic.sosActive? "\"\", " : "") + //currently, it isn't made available via sos
                (EDStatic.wcsActive? "\"\", " : "") +
                "\"\", \"GLOBEC NEP Rosette Bottle Data (2002)\", \"GLOBEC (GLOBal " +
                "Ocean ECosystems Dynamics) NEP (Northeast Pacific)\\nRosette Bottle Data from " +
                "New Horizon Cruise (NH0207: 1-19 August 2002).\\nNotes:\\nPhysical data " +
                "processed by Jane Fleischbein (OSU).\\nChlorophyll readings done by " +
                "Leah Feinberg (OSU).\\nNutrient analysis done by Burke Hales (OSU).\\n" +
                "Sal00 - salinity calculated from primary sensors (C0,T0).\\n" +
                "Sal11 - salinity calculated from secondary sensors (C1,T1).\\n" +
                "secondary sensor pair was used in final processing of CTD data for\\n" +
                "most stations because the primary had more noise and spikes. The\\n" +
                "primary pair were used for cast #9, 24, 48, 111 and 150 due to\\n" +
                "multiple spikes or offsets in the secondary pair.\\n" +
                "Nutrient samples were collected from most bottles; all nutrient data\\n" +
                "developed from samples frozen during the cruise and analyzed ashore;\\n" +
                "data developed by Burke Hales (OSU).\\n" +
                "Operation Detection Limits for Nutrient Concentrations\\n" +
                "Nutrient  Range         Mean    Variable         Units\\n" +
                "PO4       0.003-0.004   0.004   Phosphate        micromoles per liter\\n" +
                "N+N       0.04-0.08     0.06    Nitrate+Nitrite  micromoles per liter\\n" +
                "Si        0.13-0.24     0.16    Silicate         micromoles per liter\\n" +
                "NO2       0.003-0.004   0.003   Nitrite          micromoles per liter\\n" +
                "Dates and Times are UTC.\\n\\n" +
                "For more information, see\\n" +
                "http://cis.whoi.edu/science/bcodmo/dataset.cfm?id=10180&flag=view\\n\\n" +
                "Inquiries about how to access this data should be directed to\\n" +
                "Dr. Hal Batchelder (hbatchelder@coas.oregonstate.edu).\\n\\n" +
                "cdm_data_type = TrajectoryProfile\\n" +
                "VARIABLES:\\ncruise_id\\n... (24 more variables)\\n\", " +
                "\"http://127.0.0.1:8080/cwexperimental/info/erdGlobecBottle/index.json\", " +
                "\"http://oceanwatch.pfeg.noaa.gov/thredds/PaCOOS/GLOBEC/catalog.html?" +
                "dataset=GLOBEC_Bottle_data\", \"http://127.0.0.1:8080/cwexperimental/rss/" +
                "erdGlobecBottle.rss\", \"http://127.0.0.1:8080/cwexperimental/subscriptions/" +
                "add.html?datasetID=erdGlobecBottle&showErrors=false&email=\", \"GLOBEC\", " +
                "\"erdGlobecBottle\"],";
            po = results.indexOf("http://127.0.0.1:8080/cwexperimental/tabledap/erdGlobecBottle");
            Test.ensureEqual(results.substring(po, po + expected.length()), expected, "results=\n" + results);

            //griddap
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/index.html");            
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Datasets Which Can Be Accessed via griddap") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">Title\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">RSS\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                ">SST, Blended, Global, EXPERIMENTAL (5 Day Composite)\n") >= 0,
                "results=\n" + results);
            Test.ensureTrue(results.indexOf(">erdMHchla8day\n") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/index.json");
            Test.ensureTrue(results.indexOf("\"table\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"Title\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"RSS\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                "\"SST, Blended, Global, EXPERIMENTAL (5 Day Composite)\"") >= 0,
                "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"erdMHchla8day\"") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/erdMHchla8day.html");            
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Data Access Form") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Make A Graph") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("(Centered Time, UTC)") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("chlorophyll") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Just generate the URL:") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/griddap/erdMHchla8day.graph");            
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Make A Graph") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Data Access Form") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("(UTC)") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("chlorophyll") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Download the Data or an Image") >= 0, "results=\n" + results);


            //tabledap
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/index.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Datasets Which Can Be Accessed via tabledap") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">Title\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">RSS\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">GLOBEC NEP Rosette Bottle Data (2002)\n") >= 0,
                "results=\n" + results);            
            Test.ensureTrue(results.indexOf(">erdGlobecBottle\n") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/index.json");
            Test.ensureTrue(results.indexOf("\"table\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"Title\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"RSS\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"GLOBEC NEP Rosette Bottle Data (2002)\"") >= 0,
                "results=\n" + results);            
            Test.ensureTrue(results.indexOf("\"erdGlobecBottle\"") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/erdGlobecBottle.html");            
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Data Access Form") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Make A Graph") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("(UTC)") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("NO3") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Just generate the URL:") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/tabledap/erdGlobecBottle.graph");            
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Make A Graph") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Data Access Form") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("NO3") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Filled Square") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Download the Data or an Image") >= 0, "results=\n" + results);

            //sos
            if (EDStatic.sosActive) {
                results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/sos/index.html");
                Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("Datasets Which Can Be Accessed via SOS") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf(">Title</th>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf(">RSS</th>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf(">NDBC Standard Meteorological Buoy Data</td>") >= 0,
                    "results=\n" + results);            
                Test.ensureTrue(results.indexOf(">cwwcNDBCMet<") >= 0, "results=\n" + results);

                results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/sos/index.json");
                Test.ensureTrue(results.indexOf("\"table\"") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("\"Title\"") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("\"RSS\"") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("\"NDBC Standard Meteorological Buoy Data\"") >= 0,
                    "results=\n" + results);            
                Test.ensureTrue(results.indexOf("\"cwwcNDBCMet\"") >= 0, "results=\n" + results);

                results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/sos/documentation.html");            
                Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf(
                    "ERDDAP makes some datasets available via ERDDAP's Sensor Observation Service (SOS) web service.") >= 0, 
                    "results=\n" + results);

                String sosUrl = EDStatic.erddapUrl + "/sos/cwwcNDBCMet/" + EDDTable.sosServer;
                results = SSR.getUrlResponseString(sosUrl + "?service=SOS&request=GetCapabilities");            
                Test.ensureTrue(results.indexOf("<ows:ServiceIdentification>") >= 0, "results=\n" + results);            
                Test.ensureTrue(results.indexOf("<ows:Get xlink:href=\"" + sosUrl + "\"/>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("</Capabilities>") >= 0, "results=\n" + results);
            }

            //wcs
            if (EDStatic.wcsActive) {
                results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/wcs/index.html");
                Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("Datasets Which Can Be Accessed via WCS") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf(">Title</th>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf(">RSS</th>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf(">Chlorophyll-a, Aqua MODIS, NPP, Global, Science Quality (8 Day Composite)</td>") >= 0,
                    "results=\n" + results);            
                Test.ensureTrue(results.indexOf(">erdMHchla8day<") >= 0, "results=\n" + results);

                results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/wcs/index.json");
                Test.ensureTrue(results.indexOf("\"table\"") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("\"Title\"") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("\"RSS\"") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("\"Chlorophyll-a, Aqua MODIS, NPP, Global, Science Quality (8 Day Composite)\"") >= 0,
                    "results=\n" + results);            
                Test.ensureTrue(results.indexOf("\"erdMHchla8day\"") >= 0, "results=\n" + results);

                results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/wcs/documentation.html");            
                Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf(
                    "ERDDAP makes some datasets available via ERDDAP's Web Coverage Service (WCS) web service.") >= 0, 
                    "results=\n" + results);

                String wcsUrl = EDStatic.erddapUrl + "/wcs/erdMHchla8day/" + EDDGrid.wcsServer;
                results = SSR.getUrlResponseString(wcsUrl + "?service=WCS&request=GetCapabilities");            
                Test.ensureTrue(results.indexOf("<CoverageOfferingBrief>") >= 0, "results=\n" + results);            
                Test.ensureTrue(results.indexOf("<lonLatEnvelope srsName") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("</WCS_Capabilities>") >= 0, "results=\n" + results);
            }

            //wms
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/wms/index.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Datasets Which Can Be Accessed via WMS") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">Title\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">RSS\n") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(">Chlorophyll-a, Aqua MODIS, NPP, Global, Science Quality (8 Day Composite)\n") >= 0,
                "results=\n" + results);            
            Test.ensureTrue(results.indexOf(">erdMHchla8day\n") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/wms/index.json");
            Test.ensureTrue(results.indexOf("\"table\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"Title\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"RSS\"") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("\"Chlorophyll-a, Aqua MODIS, NPP, Global, Science Quality (8 Day Composite)\"") >= 0,
                "results=\n" + results);            
            Test.ensureTrue(results.indexOf("\"erdMHchla8day\"") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/wms/documentation.html");            
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("display of registered and superimposed map-like views") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Three Ways to Make Maps with WMS") >= 0, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/wms/erdMHchla8day/index.html");            
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Chlorophyll-a, Aqua MODIS, NPP, Global, Science Quality (8 Day Composite)") >= 0,
                "results=\n" + results);            
            Test.ensureTrue(results.indexOf("Data Access Form") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Make A Graph") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("on-the-fly by ERDDAP's") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("altitude") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf("Three Ways to Make Maps with WMS") >= 0, "results=\n" + results);

//            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
//                "/categorize/standard_name/index.html");
//            Test.ensureTrue(results.indexOf(">sea_water_temperature<") >= 0,
//                "results=\n" + results);

            //validate the various GetCapabilities documents
/*            String s = "http://www.validome.org/xml/validate/?lang=en" +
                "&url=" + EDStatic.erddapUrl + "/wms/" + WMS_SERVER + "?service=WMS&" +
                "request=GetCapabilities&version=";
            SSR.displayInBrowser(s + "1.1.0");
            SSR.displayInBrowser(s + "1.1.1");
            SSR.displayInBrowser(s + "1.3.0");
*/

            //more information
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/information.html");
            Test.ensureTrue(results.indexOf(
                "ERDDAP a solution to everyone's data distribution / data access problems?") >= 0,
                "results=\n" + results);

            //subscriptions
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/subscriptions/index.html");
            if (EDStatic.subscriptionSystemActive) {
                Test.ensureTrue(results.indexOf("Add a new subscription") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("Validate a subscription") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("List your subscriptions") >= 0, "results=\n" + results);
                Test.ensureTrue(results.indexOf("Remove a subscription") >= 0, "results=\n" + results);

                results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                    "/subscriptions/add.html");
                Test.ensureTrue(results.indexOf(
                    "To add a (another) subscription, please fill out this form:") >= 0, 
                    "results=\n" + results);

                results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                    "/subscriptions/validate.html");
                Test.ensureTrue(results.indexOf(
                    "To validate a (another) subscription, please fill out this form:") >= 0, 
                    "results=\n" + results);

                results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                    "/subscriptions/list.html");
                Test.ensureTrue(results.indexOf(
                    "To request an email with a list of your subscriptions, please fill out this form:") >= 0, 
                    "results=\n" + results);

                results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                    "/subscriptions/remove.html");
                Test.ensureTrue(results.indexOf(
                    "To remove a (another) subscription, please fill out this form:") >= 0, 
                    "results=\n" + results);
            }


            //slideSorter
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + 
                "/slidesorter.html");
            Test.ensureTrue(results.indexOf(
                "Your slides will be lost when you close this browser window, unless you:") >= 0, 
                "results=\n" + results);


            //google Gadgets (always at coastwatch)
            results = SSR.getUrlResponseString(
                "http://coastwatch.pfeg.noaa.gov/erddap/images/gadgets/GoogleGadgets.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                "Google Gadgets with Graphs or Maps for Your iGoogle Home Page") >= 0, 
                "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                "are self-contained chunks of web content") >= 0, 
                "results=\n" + results);


            //embed a graph  (always at coastwatch)
            results = SSR.getUrlResponseString(
                "http://coastwatch.pfeg.noaa.gov/erddap/images/embed.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                "Embed a Graph in a Web Page") >= 0, 
                "results=\n" + results);

            //Computer Programs            
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/rest.html");
            Test.ensureTrue(results.indexOf("</html>") >= 0, "results=\n" + results);
            Test.ensureTrue(results.indexOf(
                "ERDDAP as a Web Service") >= 0,
                "results=\n" + results);

            //list of services
            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/index.csv");
            expected = 
"Resource, URL\n" +
"info, http://127.0.0.1:8080/cwexperimental/info/index.csv\n" +
"search, http://127.0.0.1:8080/cwexperimental/search/index.csv?searchFor=\n" +
"categorize, http://127.0.0.1:8080/cwexperimental/categorize/index.csv\n" +
"griddap, http://127.0.0.1:8080/cwexperimental/griddap/index.csv\n" +
"tabledap, http://127.0.0.1:8080/cwexperimental/tabledap/index.csv\n" +
(EDStatic.sosActive? "sos, http://127.0.0.1:8080/cwexperimental/sos/index.csv\n" : "") +
(EDStatic.wcsActive? "wcs, http://127.0.0.1:8080/cwexperimental/wcs/index.csv\n" : "") +
"wms, http://127.0.0.1:8080/cwexperimental/wms/index.csv\n";
//subscriptions?
            Test.ensureEqual(results, expected, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/index.htmlTable");
            expected = 
EDStatic.startHeadHtml(EDStatic.erddapUrl((String)null), "Resources") + "\n" +
"</head>\n" +
EDStatic.startBodyHtml(null) + "\n" +
"&nbsp;\n" +
"<form action=\"\">\n" +
"<input type=\"button\" value=\"Back\" onClick=\"history.go(-1);return true;\">\n" +
"</form>\n" +
"\n" +
"&nbsp;\n" +
"<table class=\"erd commonBGColor\" cellspacing=\"0\">\n" +
"<tr>\n" +
"<th>Resource\n" +
"<th>URL\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap>info\n" +
"<td nowrap>http://127.0.0.1:8080/cwexperimental/info/index.htmlTable\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap>search\n" +
"<td nowrap>http://127.0.0.1:8080/cwexperimental/search/index.htmlTable?searchFor=\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap>categorize\n" +
"<td nowrap>http://127.0.0.1:8080/cwexperimental/categorize/index.htmlTable\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap>griddap\n" +
"<td nowrap>http://127.0.0.1:8080/cwexperimental/griddap/index.htmlTable\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap>tabledap\n" +
"<td nowrap>http://127.0.0.1:8080/cwexperimental/tabledap/index.htmlTable\n" +
"</tr>\n" +
(EDStatic.sosActive?              
"<tr>\n" +
"<td nowrap>sos\n" +
"<td nowrap>http://127.0.0.1:8080/cwexperimental/sos/index.htmlTable\n" +
"</tr>\n" : "") +
(EDStatic.wcsActive?
"<tr>\n" +
"<td nowrap>wcs\n" +
"<td nowrap>http://127.0.0.1:8080/cwexperimental/wcs/index.htmlTable\n" +
"</tr>\n" : "") +
"<tr>\n" +
"<td nowrap>wms\n" +
"<td nowrap>http://127.0.0.1:8080/cwexperimental/wms/index.htmlTable\n" +
"</tr>\n" +
"</table>\n" +
EDStatic.endBodyHtml(EDStatic.erddapUrl((String)null)) + "\n" +
"</html>\n";
            Test.ensureEqual(results, expected, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/index.json");
            expected = 
"{\n" +
"  \"table\": {\n" +
"    \"columnNames\": [\"Resource\", \"URL\"],\n" +
"    \"columnTypes\": [\"String\", \"String\"],\n" +
"    \"rows\": [\n" +
"      [\"info\", \"http://127.0.0.1:8080/cwexperimental/info/index.json\"],\n" +
"      [\"search\", \"http://127.0.0.1:8080/cwexperimental/search/index.json?searchFor=\"],\n" +
"      [\"categorize\", \"http://127.0.0.1:8080/cwexperimental/categorize/index.json\"],\n" +
"      [\"griddap\", \"http://127.0.0.1:8080/cwexperimental/griddap/index.json\"],\n" +
"      [\"tabledap\", \"http://127.0.0.1:8080/cwexperimental/tabledap/index.json\"],\n" +
(EDStatic.sosActive? "      [\"sos\", \"http://127.0.0.1:8080/cwexperimental/sos/index.json\"],\n" : "") +
(EDStatic.wcsActive? "      [\"wcs\", \"http://127.0.0.1:8080/cwexperimental/wcs/index.json\"],\n" : "") +
"      [\"wms\", \"http://127.0.0.1:8080/cwexperimental/wms/index.json\"]\n" +
//subscriptions?
"    ]\n" +
"  }\n" +
"}\n";
            Test.ensureEqual(results, expected, "results=\n" + results);

            results = String2.annotatedString(SSR.getUrlResponseString(EDStatic.erddapUrl + "/index.tsv"));
            expected = 
"Resource[9]URL[10]\n" +
"info[9]http://127.0.0.1:8080/cwexperimental/info/index.tsv[10]\n" +
"search[9]http://127.0.0.1:8080/cwexperimental/search/index.tsv?searchFor=[10]\n" +
"categorize[9]http://127.0.0.1:8080/cwexperimental/categorize/index.tsv[10]\n" +
"griddap[9]http://127.0.0.1:8080/cwexperimental/griddap/index.tsv[10]\n" +
"tabledap[9]http://127.0.0.1:8080/cwexperimental/tabledap/index.tsv[10]\n" +
(EDStatic.sosActive? "sos[9]http://127.0.0.1:8080/cwexperimental/sos/index.tsv[10]\n" : "") +
(EDStatic.wcsActive? "wcs[9]http://127.0.0.1:8080/cwexperimental/wcs/index.tsv[10]\n" : "") +
"wms[9]http://127.0.0.1:8080/cwexperimental/wms/index.tsv[10]\n" +
"[end]";
            Test.ensureEqual(results, expected, "results=\n" + results);

            results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/index.xhtml");
            expected = 
"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
"<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\"\n" +
"  \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n" +
"<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
"<head>\n" +
"  <meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\" />\n" +
"  <title>Resources</title>\n" +
"</head>\n" +
"<body style=\"color:black; background:white; font-family:Arial,Helvetica,sans-serif; font-size:85%; line-height:130%;\">\n" +
"\n" +
"&nbsp;\n" +
"<table border=\"1\" cellpadding=\"2\" cellspacing=\"0\">\n" +
"<tr>\n" +
"<th>Resource</th>\n" +
"<th>URL</th>\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap=\"nowrap\">info</td>\n" +
"<td nowrap=\"nowrap\">http://127.0.0.1:8080/cwexperimental/info/index.xhtml</td>\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap=\"nowrap\">search</td>\n" +
"<td nowrap=\"nowrap\">http://127.0.0.1:8080/cwexperimental/search/index.xhtml?searchFor=</td>\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap=\"nowrap\">categorize</td>\n" +
"<td nowrap=\"nowrap\">http://127.0.0.1:8080/cwexperimental/categorize/index.xhtml</td>\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap=\"nowrap\">griddap</td>\n" +
"<td nowrap=\"nowrap\">http://127.0.0.1:8080/cwexperimental/griddap/index.xhtml</td>\n" +
"</tr>\n" +
"<tr>\n" +
"<td nowrap=\"nowrap\">tabledap</td>\n" +
"<td nowrap=\"nowrap\">http://127.0.0.1:8080/cwexperimental/tabledap/index.xhtml</td>\n" +
"</tr>\n" +
(EDStatic.sosActive?
"<tr>\n" +
"<td nowrap=\"nowrap\">sos</td>\n" +
"<td nowrap=\"nowrap\">http://127.0.0.1:8080/cwexperimental/sos/index.xhtml</td>\n" +
"</tr>\n" : "") +
(EDStatic.wcsActive?
"<tr>\n" +
"<td nowrap=\"nowrap\">wcs</td>\n" +
"<td nowrap=\"nowrap\">http://127.0.0.1:8080/cwexperimental/wcs/index.xhtml</td>\n" +
"</tr>\n" : "") +
"<tr>\n" +
"<td nowrap=\"nowrap\">wms</td>\n" +
"<td nowrap=\"nowrap\">http://127.0.0.1:8080/cwexperimental/wms/index.xhtml</td>\n" +
"</tr>\n" +
"</table>\n" +
"</body>\n" +
"</html>\n";
            Test.ensureEqual(results, expected, "results=\n" + results);


        } catch (Throwable t) {
            String2.getStringFromSystemIn(MustBe.throwableToString(t) + 
"\nThese tests don't work if the localhost erddap is configured to look like POST." +
                "\nError accessing " + EDStatic.erddapUrl +
                "\nPress ^C to stop or Enter to continue..."); 
        }
    }

    /** This repeatedly gets the info/index.html web page and ensures it is without error. 
     *  It is best tu run this when many datasets are loaded. 
     *  For a harder test: run this 4X simultaneously. */
    public static void testHammerGetDatasets() throws Throwable {
        Erddap.verbose = true;
        Erddap.reallyVerbose = true;
        EDD.testVerboseOn();
        String results, expected;
        String2.log("\n*** Erddap.testHammerGetDatasets");
        int count = -5; //let it warm up
        long sumTime = 0;

        try {
            while (true) {
                if (count == 0) sumTime = 0;
                sumTime -= System.currentTimeMillis();
                //if uncompressed, it is 1Thread=280 4Threads=900ms
                results = SSR.getUncompressedUrlResponseString(EDStatic.erddapUrl + "/info/index.html"); 
                //if compressed, it is 1Thread=1575 4=Threads=5000ms
                //results = SSR.getUrlResponseString(EDStatic.erddapUrl + "/info/index.html"); 
                sumTime += System.currentTimeMillis();
                count++;
                if (count > 0) String2.log("count=" + count + " AvgTime=" + (sumTime / count));
                expected = "List of All Datasets";
                Test.ensureTrue(results.indexOf(expected) >= 0, 
                    "results=\n" + results.substring(0, Math.min(results.length(), 5000)));
                expected = "dataset(s)";
                Test.ensureTrue(results.indexOf(expected) >= 0,
                    "results=\n" + results.substring(0, Math.min(results.length(), 5000)));
            }
        } catch (Throwable t) {
            String2.log(MustBe.throwableToString(t));
        }
    }

    /**
     * This is used by Bob to do simple tests of the basic Erddap services 
     * from the ERDDAP at EDStatic.erddapUrl. It assumes Bob's test datasets are available.
     *
     */
    public static void test() throws Throwable {
        testBasic();
    }

}



