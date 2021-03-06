package org.nyu.edu.dlts.utils;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.*;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;

/**
 * Created by IntelliJ IDEA.
 * User: nathan
 * Date: 9/6/12
 * Time: 3:59 PM
 * <p/>
 * This class handles all posting and reading from the ASpace project
 */
public class ASpaceClient {
    public static final String ADMIN_LOGIN_ENDPOINT = "/users/admin/login";
    public static final String SUBJECT_ENDPOINT = "/subjects";
    public static final String CLASSIFICATION_ENDPOINT = "/classifications";
    public static final String CLASSIFICATION_TERM_ENDPOINT = "/classification_terms";
    public static final String REPOSITORY_ENDPOINT = "/repositories";
    public static final String ADMIN_REPOSITORY_ENDPOINT = "/repositories/1";
    public static final String GROUP_ENDPOINT = "/groups";
    public static final String LOCATION_ENDPOINT = "/locations";
    public static final String USER_ENDPOINT = "/users";
    public static final String VOCABULARY_ENDPOINT = "/vocabularies";
    public static final String ACCESSION_ENDPOINT = "/accessions";
    public static final String EVENT_ENDPOINT = "/events";
    public static final String COLLECTION_MANAGEMENT_RECORD_ENDPOINT = "/collection_management";
    public static final String RESOURCE_ENDPOINT = "/resources";
    public static final String ARCHIVAL_OBJECT_ENDPOINT = "/archival_objects";
    public static final String DIGITAL_OBJECT_ENDPOINT = "/digital_objects";
    public static final String DIGITAL_OBJECT_COMPONENT_ENDPOINT = "/digital_object_components";
    public static final String AGENT_CORPORATE_ENTITY_ENDPOINT = "/agents/corporate_entities";
    public static final String AGENT_FAMILY_ENDPOINT = "/agents/families";
    public static final String AGENT_PEOPLE_ENDPOINT = "/agents/people";
    public static final String AGENT_SOFTWARE_ENDPOINT = "/agents/software";
    public static final String ENUM_ENDPOINT = "/config/enumerations";
    public static final String BATCH_IMPORT_ENDPOINT = "/batch_imports?migration=ArchivistToolkit";

    private HttpClient httpclient = new HttpClient();
    private String host = "";
    private String username = "";
    private String password = "";

    // String that stores the session
    private String session;

    // used to increment the error count
    private ASpaceCopyUtil aspaceCopyUtil;

    // let keep all the errors we encounter so we can have a log
    private StringBuilder errorBuffer = new StringBuilder();

    // a stop watch object to allowing pausing of the indexer
    private String indexerHost = "";
    private long pauseTimeInSec = 43200; // pause indexer for 12 hours initially
    private StopWatch stopWatch = new StopWatch();

    private boolean doPause = false;
    private boolean firstTimePaused = true;

    // boolean to use when one once debug stuff
    private boolean debug = false;

    /**
     * The main constructor
     *
     * @param host
     * @param username
     * @param password
     */
    public ASpaceClient(String host, String username, String password) {
        this.host = host;
        this.username = username;
        this.password = password;
    }

    /**
     * Constructor that takes a host name and valid session
     *
     * @param host
     * @param session
     */
    public ASpaceClient(String host, String session) {
        this.host = host;
        this.session = session;
    }

    /**
     * Method to set the aspace copy util
     *
     * @param aspaceCopyUtil
     */
    public void setASpaceCopyUtil(ASpaceCopyUtil aspaceCopyUtil) {
        this.aspaceCopyUtil = aspaceCopyUtil;
    }

    /**
     * Method to get the session using the admin login
     */
    public boolean getSession() {
        boolean haveSession = false;

        // get a session id using the admin login
        Part[] parts = new Part[2];
        parts[0] = new StringPart("password", password);
        parts[1] = new StringPart("expiring", "false");

        String fullUrl = host + ADMIN_LOGIN_ENDPOINT;

        PostMethod post = new PostMethod(fullUrl);
        post.setRequestEntity(new MultipartRequestEntity(parts, post.getParams()));

        if (debug) System.out.println("post: " + fullUrl);

        try {
            String id = executePost(post, "session", "N/A", "N/A");

            if (!id.isEmpty()) {
                session = id;
                haveSession = true;

                // set the indexer host here for convenience sake. This assumes that the
                // default indexer port of 8090 was not changed
                indexerHost = host.replace("89", "90");
            }
        } catch (Exception e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        // start the stop watch object
        stopWatch.start();

        // session was generated so return true
        return haveSession;
    }

    /**
     * Method to do a post to the json
     *
     * @param route
     * @param jsonText
     * @return
     */
    public String post(String route, String jsonText, NameValuePair[] params, String atId) throws Exception {
        // Prepare HTTP post method.
        String fullUrl = host + route;
        PostMethod post = new PostMethod(fullUrl);
        post.setRequestEntity(new StringRequestEntity(jsonText, "application/json", null));

        // set any parameters
        if (params != null) {
            post.setQueryString(params);
        }

        // add session to the header if it's not null
        if (session != null) {
            post.setRequestHeader("X-ArchivesSpace-Session", session);
        }

        if (debug) System.out.println("post: " + fullUrl);

        // set the idName depending on the type of record being posted
        String idName = "id";
        if (route.contains(BATCH_IMPORT_ENDPOINT)) {
            idName = "saved";

            // since we dont want to keep large files around if text is bigger than 10 MB then
            // then reset jsonText
            if (jsonText.length() > 1048576 * 10) {
                jsonText = "{ /* Record greater than 10 MB */}";
            }
        }

        return executePost(post, idName, atId, jsonText);
    }

    /**
     * Method to actually execute the post method
     *
     * @param post
     * @param idName   used to specify what the name of the id is in json text
     * @param arId     A quick way to identify the record that generated any errors
     * @param jsonText Only used to return with the error message if needed
     * @return The id or session
     * @throws Exception
     */
    private String executePost(PostMethod post, String idName, String arId, String jsonText) throws Exception {
        String id = "";

        // Execute request
        try {
            int statusCode = httpclient.executeMethod(post);

            // Display status code
            String statusMessage = "Status code: " + statusCode +
                    "\nStatus text: " + post.getStatusText();

            if (debug) System.out.println(statusMessage);

            // Display response
            String responseBody = post.getResponseBodyAsString();

            if (debug) {
                System.out.println("Response body: ");
                System.out.println(responseBody);
            }

            // now check to make sure the id is not null or empty
            //System.out.println(post.getURI() + "\nBody Text: " + responseBody + "\n");

            // if status code doesn't equal to success throw exception
            if (statusCode == HttpStatus.SC_OK) {
                JSONObject response;

                if (responseBody.contains("\"errors\":[")) {
                    JSONArray responseJA = new JSONArray(responseBody);
                    response = responseJA.getJSONObject(responseJA.length() - 1);

                    errorBuffer.append("Endpoint: ").append(post.getURI()).append("\n").
                            append("AR Identifier:").append(arId).append("\n").
                            append(statusMessage).append("\n\n").append(response.toString(2)).append("\n");

                    throw new Exception(response.toString(2));
                } else if (responseBody.contains("{\"saved\":")) {
                    JSONArray responseJA = new JSONArray(responseBody);
                    response = responseJA.getJSONObject(responseJA.length() - 1);
                } else {
                    response = new JSONObject(responseBody);
                }

                id = response.getString(idName);

                if (id == null || id.trim().isEmpty()) {
                    errorBuffer.append("Endpoint: ").append(post.getURI()).append("\n").
                            append("AR Identifier: ").append(arId).append("\n").
                            append(statusMessage).append("\n\n").append(response.toString(2)).append("\n");

                    throw new Exception(response.toString(2));
                }

                if (debug) System.out.println(response.toString(2));
            } else if (statusCode == HttpStatus.SC_BAD_REQUEST && responseBody.contains("\"conflicting_record\":[\"")) {
                // ArchivesSpace will send back a "Bad Request" response if you
                // try to create a subject or agent that already exists.  In the
                // JSON response, it also gives the URI of the record that
                // caused the conflict.
                //
                // Return the ID of the conflicting record to re-use that
                // record.

                JSONObject response = new JSONObject(responseBody);
                JSONArray conflictingRecords = response.getJSONObject("error").getJSONArray("conflicting_record");

                String conflictingUri = conflictingRecords.getString(0);
                id = conflictingUri.substring(conflictingUri.lastIndexOf(" ") + 1);

                errorBuffer.append("Endpoint: ").append(post.getURI()).append("\n").
                        append("AR Identifier: ").append(arId).append("\n").
                        append("Re-using existing ASpace record: ").append(conflictingUri).append("\n\n");

                aspaceCopyUtil.incrementASpaceErrorCount();
                aspaceCopyUtil.incrementSaveErrorCount();
            } else {
                // if it a 500 error the ASpace then we may need to add the JSON text
                if (statusCode == HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                    if (responseBody.contains("PoolTimeout")) {
                        responseBody = "Error: Sequel Pool Timeout ...";
                    } else if (responseBody.contains("OutOfMemory")) {
                        responseBody = "Fatal Error: ArchivesSpace Backend Crashed (OutOfMemoryError)\nPlease Restart ...";
                    } else if (responseBody.contains("ThreadError")) {
                        responseBody = "Fatal Error: ArchivesSpace Backend Crashed (OutOfStackSpaceError)\nPlease Restart ...";
                    }
                }

                errorBuffer.append("Endpoint: ").append(post.getURI()).append("\n").
                        append("AR Identifier:").append(arId).append("\n").
                        append(statusMessage).append("\n").append(responseBody).append("\n\n");

                post.releaseConnection();
                throw new Exception(statusMessage);
            }
        } finally {
            // Release current connection to the server
            post.releaseConnection();
        }

        return id;
    }

    /**
     * Method to return a JSON object from the call a get method
     *
     * @param endpoint Location of resource
     * @return
     * @throws Exception
     */
    public String get(String endpoint, NameValuePair[] params) throws Exception {
        String fullUrl = host + endpoint;
        GetMethod get = new GetMethod(fullUrl);

        // set any parameters
        if (params != null) {
            get.setQueryString(params);
        }

        // add session to the header if it's not null
        if (session != null) {
            get.setRequestHeader("X-ArchivesSpace-Session", session);
        }

        // set the token in the header
        //get.setRequestHeader("Authorization", "OAuth " + accessToken);
        String responseBody = null;

        try {
            if (debug) System.out.println("get: " + fullUrl);

            int statusCode = httpclient.executeMethod(get);

            String statusMessage = "Status code: " + statusCode +
                    "\nStatus text: " + get.getStatusText();

            if (get.getStatusCode() == HttpStatus.SC_OK) {
                try {
                    responseBody = get.getResponseBodyAsString();

                    if (debug) System.out.println("response: " + responseBody);
                } catch (Exception e) {
                    errorBuffer.append(statusMessage).append("\n\n").append(responseBody).append("\n");
                    e.printStackTrace();
                    throw e;
                }
            } else {
                errorBuffer.append(statusMessage).append("\n");
            }
        } finally {
            get.releaseConnection();
        }

        return responseBody;
    }

    /**
     * Method to delete a record on the aspace backend. Mainly useful for testing purposes
     *
     * @param route
     * @return
     */
    public String deleteRecord(String route) throws Exception {
        String fullUrl = host + route;
        DeleteMethod delete = new DeleteMethod(fullUrl);

        // add session to the header if it's not null
        if (session != null) {
            delete.setRequestHeader("X-ArchivesSpace-Session", session);
        }

        int statusCode = httpclient.executeMethod(delete);

        String statusMessage = "Status code: " + statusCode +
                "\nStatus text: " + delete.getStatusText();

        if (debug) {
            System.out.println("delete: " + fullUrl + "\n" + statusMessage);
        }

        delete.releaseConnection();

        return statusMessage;
    }

    /**
     * Method to return the repositories in the ASpace database
     *
     * @return
     */
    public HashMap<String, String> loadRepositories() {
        HashMap<String, String> repos = new HashMap<String, String>();

        try {
            String jsonText = get(REPOSITORY_ENDPOINT, null);
            JSONArray jsonArray = new JSONArray(jsonText);

            if (jsonArray.length() != 0) {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject json = (JSONObject) jsonArray.get(i);
                    String shortName = (String) json.get("repo_code");
                    String uri = (String) json.get("uri");
                    repos.put(shortName, uri);
                }

                return repos;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Method to load the group for a particular repository
     *
     * @param repoURI The API of the repository
     * @return
     */
    public JSONArray loadRepositoryGroups(String repoURI) {
        String fullUrl = repoURI + GROUP_ENDPOINT;

        try {
            NameValuePair[] params = new NameValuePair[1];
            params[0] = new NameValuePair("page", "1");

            String jsonText = get(fullUrl, params);
            JSONArray groups = new JSONArray();

            // make this null safe in case we are in test mode
            if (jsonText != null) {
                groups = new JSONArray(jsonText);
            }

            return groups;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Method to load the dynamic enum list
     *
     * @return Method containing the enums
     */
    public HashMap<String, JSONObject> loadDynamicEnums() {
        HashMap<String, JSONObject> dynamicEnums = new HashMap<String, JSONObject>();

        try {
            String jsonText = get(ENUM_ENDPOINT, null);
            JSONArray jsonArray = new JSONArray(jsonText);

            if (jsonArray.length() != 0) {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject json = (JSONObject) jsonArray.get(i);
                    String name = (String) json.get("name");
                    dynamicEnums.put(name, json);
                }

                return dynamicEnums;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Method to get any error messages that occurred while talking to the ASpace backend
     *
     * @return String containing error messages
     */
    public String getErrorMessages() {
        return errorBuffer.toString();
    }

    /**
     * Method to return information about the archives space backend
     *
     * @return
     */
    public String getArchivesSpaceInformation() {
        String info = "Unknown Archives Space Version ...";

        try {
            info = get("", null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return info;
    }

    /**
     * Method to return a record as formatted json
     *
     * @param uri
     * @return
     */
    public String getRecordAsJSON(String uri, String paramString) {
        try {
            NameValuePair[] params = getParams(paramString);

            String jsonText = get(uri, params);

            if (jsonText != null && !jsonText.isEmpty()) {
                if (jsonText.startsWith("[{")) {
                    JSONArray json = new JSONArray(jsonText);
                    return json.toString(4);
                } else {
                    JSONObject json = new JSONObject(jsonText);
                    return json.toString(4);
                }
            }
        } catch (Exception e) {
            errorBuffer.append(e.toString());
        }

        return null;
    }

    /**
     * Method to get the params from a comma separated string
     *
     * @param paramString
     * @return
     */
    private NameValuePair[] getParams(String paramString) {
        String[] parts = paramString.split("\\s*,\\s*");

        // make sure we have parameters, otherwise exit
        if (paramString.isEmpty() || parts.length < 1) {
            return null;
        } else {
            NameValuePair[] params = new NameValuePair[parts.length];

            for (int i = 0; i < parts.length; i++) {
                try {
                    String[] sa = parts[i].split("\\s*=\\s*");
                    params[i] = new NameValuePair(sa[0], sa[1]);
                } catch (Exception e) {
                    return null;
                }
            }

            return params;
        }
    }

    /**
     * Method to return an ASpace client instance which has the same session as this object
     *
     * @return
     */
    public synchronized ASpaceClient getAuthenticatedClient() {
        return new ASpaceClient(host, session);
    }

    /**
     * Method to allow child aspace clients to append error messages
     *
     * @param errorMessage
     */
    public synchronized void appendToErrorBuffer(String errorMessage) {
        errorBuffer.append(errorMessage);
    }
}
