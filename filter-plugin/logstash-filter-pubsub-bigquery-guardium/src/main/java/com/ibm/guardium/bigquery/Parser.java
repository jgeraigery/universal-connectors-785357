/*
Copyright IBM Corp. 2021, 2023 All rights reserved.
SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.guardium.bigquery;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;
import com.ibm.guardium.universalconnector.commons.structures.Accessor;
import com.ibm.guardium.universalconnector.commons.structures.Construct;
import com.ibm.guardium.universalconnector.commons.structures.ExceptionRecord;
import com.ibm.guardium.universalconnector.commons.structures.Record;
import com.ibm.guardium.universalconnector.commons.structures.Sentence;
import com.ibm.guardium.universalconnector.commons.structures.SentenceObject;
import com.ibm.guardium.universalconnector.commons.structures.SessionLocator;
import com.ibm.guardium.universalconnector.commons.structures.Time;

/**
 * Parser Class will perform operation on parsing events and messages from the
 * BigQuery audit logs into a Guardium record instance Guardium records include
 * the accessor, the sessionLocator, data, and exceptions. If there are no
 * errors, the data contains details about the query "construct"
 *
 * @className @Parser
 * 
 */
public class Parser {
	private static Logger logger = LogManager.getLogger(Parser.class);

	/**
	 * Few set operators are not supported Due to parser limitations,Hence handling
	 * in regex to get object and verb values
	 * 
	 */

	private static String customRegex = "((?i)\\s*intersect\\s*(?i)all)|((?i)\\s*INTERSECT(?i)\\s*DISTINCT)|((?i)\\s*except\\s*(?i)All)|((?i)\\s*EXCEPT\\s*(?i)DISTINCT)";
	private static Pattern customRegexPattern = Pattern.compile(customRegex);
	/**
	 * Using regex, Hiding sensitive information from all the Queries
	 * 
	 */
	private static String redactedRegex = "\"[^\"]*\"|'[^']*'|\\b[\\d]+\\b";
	private static Pattern redactedRegexpattern = Pattern.compile(redactedRegex);

	final static String SCHEMA_REGEX1 = "(\\w+\\s+(?i)schema)\\s+([\\w]+)";
	final static String SCHEMAEXISTS_REGEX = "((?i)if\\s+exists)|((?i)if\\s+(?i)not\\s+exists)";

	private static Pattern SCHEMA_PATTERN1 = Pattern.compile(SCHEMA_REGEX1);
	private static Pattern SCHEMAEXISTS_PATTERN = Pattern.compile(SCHEMAEXISTS_REGEX);

	/**
	 * parseRecord() method will perform operation on JsonObject input, convert
	 * JsonObject into Record Object and then return the value as response
	 * 
	 * @param JsonObject inputJson
	 * @methodName @parseRecord
	 * @return Record GUARDIUM Object
	 * @throws Exception
	 * 
	 */
	public static Record parseRecord(JsonObject inputJson) {
		Record record = new Record();

		JsonObject protoPayload = inputJson.get(ApplicationConstants.PROTO_PAYLOAD).getAsJsonObject();
		JsonObject metaDataJson = protoPayload.get(ApplicationConstants.METADATA).getAsJsonObject();
		BigQueryDTO bigQueryDTO = null;
		String appUserName = getAppUserName(protoPayload);
		String sql = StringUtils.EMPTY;
		String databaseName = StringUtils.EMPTY;
		String auditType = StringUtils.EMPTY;
		String projectId = getTypeMetaData(inputJson);

		if (protoPayload.has(ApplicationConstants.SERVICE_DATA))
			auditType = ApplicationConstants.SERVICE_DATA;
		else if (protoPayload.has(ApplicationConstants.METADATA))
			auditType = ApplicationConstants.METADATA;

		if (!metaDataJson.entrySet().isEmpty()) {
			sql = getEventQueryFromMetaData(metaDataJson);
			databaseName = getDatabaseNameFromMetaData(inputJson);
		}
		if (StringUtils.isEmpty(sql)) {
			sql = getSql(sql, inputJson, protoPayload, metaDataJson);
		}

		if (!StringUtils.isEmpty(sql)) {
			sql = sql.replaceAll("((?i)\\u00a0)", " ");
		}
		
		record.setException(parseException(sql, inputJson, auditType, protoPayload, metaDataJson));
		
		
		if (null == record.getException()) {
			bigQueryDTO = parseAsConstruct(sql);
			record.setData(bigQueryDTO.getData());
			if (StringUtils.isEmpty(databaseName) && !StringUtils.isEmpty(sql)) {
				databaseName = !bigQueryDTO.getDBName().isEmpty() ? bigQueryDTO.getDBName().toArray()[0].toString()
						: StringUtils.EMPTY;
				if (StringUtils.isEmpty(databaseName)) {
					databaseName = getDataSetNameBySQLQuery(sql);
				}
			}
		}
		record.setDbName(projectId + ":" + databaseName);
		record.setAppUserName(appUserName);
		record.setAccessor(parseAccessor(appUserName, inputJson, databaseName, sql,
				getFieldValueByKey(protoPayload, ApplicationConstants.SERVICE_NAME)));
		record.setSessionLocator(parserSesstionLocator(inputJson, protoPayload));
		record.setTime(parseTime(getFieldValueByKey(inputJson, ApplicationConstants.TIMESTAMP)));
		record.setSessionId(getSessionHash(record.getSessionLocator().getClientIp(),
				record.getSessionLocator().getClientPort(), record.getAppUserName(), databaseName));
		

		return record;
	}

	/**
	 * getDataSetNameBySQLQuery() method will perform operation on String inputs,
	 * set the expected value into respective dataset name and then return the value
	 * as response
	 * 
	 * @param String sql
	 * @methodName @getDataSetNameBySQLQuery
	 * @return String value
	 * 
	 */
	private static String getDataSetNameBySQLQuery(String sql) {
		String dbName = StringUtils.EMPTY;
		Matcher matcher3 = null;
		try {
			Matcher removeMatcher = SCHEMAEXISTS_PATTERN.matcher(sql);
			String rSql = removeMatcher.replaceAll(StringUtils.EMPTY).trim();
			String sqlDb = CommonUtils.removeEscapeSequence(rSql);
			String sequal = sqlDb.replaceAll(Pattern.quote("`"), ApplicationConstants.UNKOWN_STRING);
			matcher3 = SCHEMA_PATTERN1.matcher(sequal);
			if (matcher3.find()) {
				matcher3.group();
				dbName = matcher3.group().split("\\s+")[2].trim();
			}
		} catch (Exception ex) {
			logger.error("Exception Found in getObjectFromAdvanceQuery::: ", ex.getCause());
		}
		return dbName;
	}

	/**
	 * parseAccessor() method will perform operation on String inputs, set the
	 * expected value into respective Accessor Object and then return the value as
	 * response
	 * 
	 * @param String     appUserName
	 * @param JsonObject protoPayload
	 * @methodName @parseAccessor
	 * @return Accessor GUARDIUM Object
	 * 
	 */
	private static Accessor parseAccessor(String appUserName, JsonObject inputJson, String databaseName, String sql,
			String serviceName) {
		Accessor accessor = new Accessor();
		String projectId = getTypeMetaData(inputJson);

		accessor.setServerType(ApplicationConstants.SERVER_TYPE_STRING);
		accessor.setServerOs(ApplicationConstants.UNKOWN_STRING);

		accessor.setClientOs(ApplicationConstants.UNKOWN_STRING);
		accessor.setClientHostName(ApplicationConstants.UNKOWN_STRING);

		accessor.setServerHostName(projectId + "_" + serviceName);

		accessor.setCommProtocol(ApplicationConstants.UNKOWN_STRING);

		accessor.setDbProtocol(ApplicationConstants.DATA_PROTOCOL_STRING);
		accessor.setDbProtocolVersion(ApplicationConstants.UNKOWN_STRING);

		accessor.setOsUser(ApplicationConstants.UNKOWN_STRING);
		accessor.setSourceProgram(ApplicationConstants.UNKOWN_STRING);

		accessor.setClient_mac(ApplicationConstants.UNKOWN_STRING);
		accessor.setServerDescription(ApplicationConstants.UNKOWN_STRING);

		accessor.setLanguage(Accessor.LANGUAGE_FREE_TEXT_STRING);
		accessor.setDataType(Accessor.DATA_TYPE_GUARDIUM_SHOULD_NOT_PARSE_SQL);

		accessor.setDbUser(appUserName);
		accessor.setServiceName(projectId + ":" + databaseName);
		return accessor;
	}

	/**
	 * getDatabaseNameFromMetaData() method will perform operation on JsonObject
	 * inputJson object convert into databaseName and return response
	 * 
	 * @param JsonObject inputJson
	 * @methodName @getDatabaseNameFromMetaData
	 * @return String
	 * 
	 */
	private static String getTypeMetaData(JsonObject inputson) {
		String projectId = StringUtils.EMPTY;
		JsonObject resourceJSON = validateKeyExistance(inputson, ApplicationConstants.RESOURCE);
		if (resourceJSON.entrySet().isEmpty() || !resourceJSON.has(ApplicationConstants.LABELS)) {
			return projectId;
		}
		JsonObject labels = resourceJSON.get(ApplicationConstants.LABELS).getAsJsonObject();
		if (!labels.has(ApplicationConstants.PROJECT_ID)) {
			return projectId;
		}
		projectId = CommonUtils.convertIntoString(labels.get(ApplicationConstants.PROJECT_ID));
		return projectId;
	}

	/**
	 * parserSesstionLocator() method will perform operation on String input, set
	 * the expected value into respective SessionLocator Object and then return the
	 * value as response
	 * 
	 * @param JsonObject inputJson
	 * @methodName @parserSesstionLocator
	 * @return SessionLocator GUARDIUM Object
	 * 
	 */
	private static SessionLocator parserSesstionLocator(JsonObject inputJson, JsonObject protoPayload) {
		String callerIp = getCallerIp(protoPayload);
		SessionLocator sessionLocator = new SessionLocator();
		sessionLocator.setIpv6(false);
		if (isValidInet6Address(callerIp)) {
			sessionLocator.setIpv6(true);
			sessionLocator.setClientIpv6(callerIp);
			sessionLocator.setServerIpv6(ApplicationConstants.DEFAULT_IPV6);
		} else {
			sessionLocator.setClientIp(callerIp);
			sessionLocator.setServerIp(ApplicationConstants.DEFAULT_IP);
		}
		sessionLocator.setClientPort(SessionLocator.PORT_DEFAULT);
		sessionLocator.setServerPort(SessionLocator.PORT_DEFAULT);
		return sessionLocator;
	}

	/**
	 * parseAsConstruct() method will perform operation on String input, set the
	 * expected value into respective Construct Object and then return the value as
	 * response
	 * 
	 * @param String sql
	 * @methodName @parseAsConstruct
	 * @return Construct GUARDIUM Object
	 * 
	 */
	private static BigQueryDTO parseAsConstruct(String sql) {
		final Construct construct = new Construct();
		final BigQueryDTO bigQueryDTO = parseSentence(sql);
		construct.sentences.add(bigQueryDTO.getSentence());
		String sqlQuery = CommonUtils.removeEscapeSequence(sql);
		if (bigQueryDTO.getSentence().getVerb().toLowerCase().contains(ApplicationConstants.TABLE)) {
			construct.setRedactedSensitiveDataSql(sqlQuery);
		} else {
			construct.setRedactedSensitiveDataSql(redactedHelper(sqlQuery));
		}
		construct.setFullSql((sqlQuery));
		bigQueryDTO.getData().setConstruct(construct);
		return bigQueryDTO;
	}

	/**
	 * getSql() method will perform operation on String input,Picking the value from
	 * response log property based on the log property forming sql queries
	 * 
	 * @param String sql, JsonObject inputJson
	 * @methodName @getSql
	 * @return Construct GUARDIUM Object
	 * 
	 */

	private static String getSql(String sql, JsonObject inputJson, JsonObject protoPayload, JsonObject metaDataJson) {
		String sqlActions = StringUtils.EMPTY;
		String reasonSql = getEventQueryFromMetaDataForUl(metaDataJson);
		String datasetID = getDatabaseNameFromMetaData(inputJson);
		String resourceName = getResourceName(protoPayload);
		String tableName = resourceName.substring(resourceName.lastIndexOf("/") + 0).replace("/", "");
		if (reasonSql.equalsIgnoreCase(ApplicationConstants.CREATE)) {
			String reason = ApplicationConstants.CREATE_SCHEMA;
			sqlActions = reason + " " + datasetID;
		} else if (reasonSql.equalsIgnoreCase(ApplicationConstants.DELETE)) {
			String reason = ApplicationConstants.DROP_SCHEMA;
			sqlActions = reason + " " + datasetID;
		} else if (reasonSql.equalsIgnoreCase(ApplicationConstants.TABLEINSERT_REQUEST)) {
			String reason = ApplicationConstants.CREATE_TABlE;
			sqlActions = reason + " " + datasetID + "." + tableName;
		} else if (reasonSql.equalsIgnoreCase(ApplicationConstants.TABLEDELETE_REQUEST)) {
			String reason = ApplicationConstants.DROP_TABLE;
			sqlActions = reason + " " + datasetID + "." + tableName;
		}
		return sqlActions;
	}

	/**
	 * this method will perform operation on String input, set the value in Sentence
	 * Object and then return the value as response
	 * 
	 * @param protoPayloadJsonObject
	 * 
	 * @param String                 sqlQuery
	 * @return sentence
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static BigQueryDTO parseSentence(String sql) {
		BigQueryDTO bigQueryDTO = null;
		Sentence sentence = null;
		List<Object> objList = new ArrayList<>();
		String arr[] = null;
		try {
			String sqlQuery = regexCustomReplace(sql);
			Map<String, Object> mp = ExecuteSqlParser.runJSEngine(sqlQuery.replaceAll(";", StringUtils.EMPTY));
			if (mp.isEmpty()) {
				sentence = new Sentence(ApplicationConstants.UNPARSEABLE);
				return new BigQueryDTO(sentence);
			} else {
				bigQueryDTO = new BigQueryDTO();
			}
			Optional<Object> optional = Optional.ofNullable(mp.get(ApplicationConstants.OBJECTS));

			if (optional.isPresent()) {
				objList = (List<Object>) mp.get(ApplicationConstants.OBJECTS);
			}
			List<String> verbList = (List<String>) mp.get(ApplicationConstants.VERBS);
			for (int i = 0; i < verbList.size(); i++) {
				arr = null;
				if (i == 0) {
					sentence = new Sentence(sqlQuery);
					bigQueryDTO.setSentence(sentence);
					if (!objList.isEmpty()) {
						if (objList.get(i) instanceof String) {
							arr = objList.get(i).toString().split("\\.");
							// Search for dbName in table name
							bigQueryDTO.getDBName().add(getDBName(arr, sql.matches("((?i)CREATE\\s+SCHEMA\\s+.*)")));
							SentenceObject sentenceObject = new SentenceObject(arr[arr.length - 1].toString()
									.replaceAll(Pattern.quote("`"), ApplicationConstants.UNKOWN_STRING));
							sentenceObject.setType(ApplicationConstants.COLLECTION);
							sentence.getObjects().add(sentenceObject);
						} else {
							for (Object object : (Set<Object>) objList.get(i)) {
								arr = object.toString().split("\\.");
								bigQueryDTO.getDBName()
										.add(getDBName(arr, sql.matches("((?i)CREATE\\s+SCHEMA\\s+.*)")));
								SentenceObject sentenceObject = new SentenceObject(arr[arr.length - 1].toString()
										.replaceAll(Pattern.quote("`"), ApplicationConstants.UNKOWN_STRING));
								sentenceObject.setType(ApplicationConstants.COLLECTION);
								sentence.getObjects().add(sentenceObject);
							}
						}
					}
					sentence.setVerb(verbList.get(i).toLowerCase());
				} else {
					Sentence descendent = new Sentence(verbList.get(i).toString());
					if (objList.get(i) instanceof String) {
						arr = objList.get(i).toString().split("\\.");
						bigQueryDTO.getDBName().add(getDBName(arr, sql.matches("((?i)CREATE\\s+SCHEMA\\s+.*)")));
						SentenceObject sentenceObject = new SentenceObject(arr[arr.length - 1].toString()
								.replaceAll(Pattern.quote("`"), ApplicationConstants.UNKOWN_STRING));
						sentenceObject.setType(ApplicationConstants.COLLECTION);
						descendent.getObjects().add(sentenceObject);
					} else {
						for (Object object : (Set<Object>) objList.get(i)) {
							arr = object.toString().split("\\.");
							bigQueryDTO.getDBName().add(getDBName(arr, sql.matches("((?i)CREATE\\s+SCHEMA\\s+.*)")));
							SentenceObject sentenceObject = new SentenceObject(arr[arr.length - 1].toString()
									.replaceAll(Pattern.quote("`"), ApplicationConstants.UNKOWN_STRING));
							sentenceObject.setType(ApplicationConstants.COLLECTION);
							descendent.getObjects().add(sentenceObject);

						}
					}
					descendent.setVerb(verbList.get(i).toLowerCase());
					sentence.getDescendants().add(descendent);
				}
			}
		} catch (Exception e) {
			logger.error("Could not find object & verb for the query : " + e.getMessage());
		}
		return bigQueryDTO;
	}

	private static String getDBName(String[] arr, boolean flag) {
		if (arr.length > 1 && !flag) {
			return arr[arr.length - 2].replaceAll(Pattern.quote("`"), ApplicationConstants.UNKOWN_STRING);
		} else if (arr.length > 1 && flag) {
			return arr[arr.length - 1].replaceAll(Pattern.quote("`"), ApplicationConstants.UNKOWN_STRING);
		}
		return ApplicationConstants.UNKOWN_STRING;
	}

	/**
	 * regexCustomReplace() method will perform operation on string and replacing
	 * the with Intersect for set operators
	 * 
	 * 
	 * @param String sqlQuery
	 * @methodName @regexCustomReplace
	 * @return String
	 * 
	 */

	private static String regexCustomReplace(String sqlQuery) {
		final Matcher matcher = customRegexPattern.matcher(sqlQuery);
		return matcher.replaceAll(ApplicationConstants.SUBSTITUTE_WITH_INTERSECT);
	}

	/**
	 * parseException() method will perform operation on String inputs and
	 * JsonObject, set the expected value into respective ExceptionRecord Object and
	 * then return the value as response
	 * 
	 * @param String     sql
	 * @param JsonObject protoPayload
	 * @param String     auditType
	 * @methodName @parseException
	 * @return ExceptionRecord GUARDIUM Object
	 * 
	 */
	public static ExceptionRecord parseException(String sql, JsonObject inputJson, String auditType,
			JsonObject protoPayload, JsonObject metaDataJson) {
		ExceptionRecord exceptionRecord = null;
		String severity = getFieldValueByKey(inputJson, ApplicationConstants.SEVERITY);
		String sqlQuery = CommonUtils.removeEscapeSequence(sql);
		switch (auditType) {
		case ApplicationConstants.SERVICE_DATA:
			String service = String.valueOf(protoPayload.get(ApplicationConstants.SERVICE_DATA));
			if (severity.equalsIgnoreCase("ERROR") || service.contains(ApplicationConstants.ERRORS)) {
				exceptionRecord = new ExceptionRecord();
				exceptionRecord.setExceptionTypeId(ApplicationConstants.EXCEPTION_TYPE_STRING);
				exceptionRecord.setDescription(getExceptionCodeAndMessage(protoPayload));
				exceptionRecord.setSqlString(sqlQuery);
			}
			break;
		case ApplicationConstants.METADATA:
			String metadata = String.valueOf(protoPayload.get(ApplicationConstants.METADATA));
			if (severity.equalsIgnoreCase("ERROR")) {
				exceptionRecord = new ExceptionRecord();
				exceptionRecord.setExceptionTypeId(ApplicationConstants.EXCEPTION_TYPE_STRING);
				exceptionRecord.setDescription(getExceptionCodeAndMessage(protoPayload));
				exceptionRecord.setSqlString(sqlQuery);
			} else if (severity.equalsIgnoreCase("INFO") && metadata.contains(ApplicationConstants.ERRORS)) {
				exceptionRecord = new ExceptionRecord();
				exceptionRecord.setExceptionTypeId(ApplicationConstants.EXCEPTION_TYPE_STRING);
				exceptionRecord.setDescription(getErrorMessage(metaDataJson));
				exceptionRecord.setSqlString(sqlQuery);
			}
			break;
		default:
			break;
		}

		return exceptionRecord;
	}

	/**
	 * parseTime() method will perform operation on String inputs, set the expected
	 * value into respective Time Object and then return the value as response
	 * 
	 * @param String dateString
	 * @methodName @parseException
	 * @return ExceptionRecord GUARDIUM Object
	 * 
	 */
	public static Time parseTime(String dateString) {
		ZonedDateTime date = ZonedDateTime.parse(dateString);
		long millis = date.toInstant().toEpochMilli();
		int minOffset = date.getOffset().getTotalSeconds() / 60;
		return new Time(millis, minOffset, 0);
	}

	/**
	 * getAppUserName() method will perform operation on JsonObject inputJson object
	 * convert into appUserName and return response
	 * 
	 * @param JsonObject inputJson
	 * @methodName @getAppUserName
	 * @return String
	 * 
	 */
	private static String getAppUserName(JsonObject protoPayloadJsonObject) {
		String appUserName = StringUtils.EMPTY;
		JsonObject authenticationJSON = validateKeyExistance(protoPayloadJsonObject,
				ApplicationConstants.AUTHENTICATION_INFO);
		if (!authenticationJSON.entrySet().isEmpty()) {
			appUserName = CommonUtils.convertIntoString(authenticationJSON.get(ApplicationConstants.PRINCIPAL_EMAIL));
		}
		return appUserName;
	}

	/**
	 * getCallerIp() method will perform operation on JsonObject inputJson object
	 * convert into callerIp and return response
	 * 
	 * @param JsonObject protoPayloadJsonObject
	 * @methodName @getCallerIp
	 * @return String
	 * 
	 */
	private static String getCallerIp(JsonObject protoPayloadJsonObject) {
		String callerIp = StringUtils.EMPTY;
		JsonObject requestMetadataIntoJSON = validateKeyExistance(protoPayloadJsonObject,
				ApplicationConstants.REQUEST_METADATA);
		if (!requestMetadataIntoJSON.entrySet().isEmpty()
				&& requestMetadataIntoJSON.has(ApplicationConstants.CALLER_IP)) {
			callerIp = CommonUtils.convertIntoString(requestMetadataIntoJSON.get(ApplicationConstants.CALLER_IP));
		}
		return callerIp;
	}

	/**
	 * isValidInet6Address() method will perform operation on String value if ipv6
	 * format is valid returns true or if it is invalid returns false
	 * 
	 * @param String ip
	 * @methodName @isValidInet6Address
	 * @return boolean value
	 * 
	 */
	public static boolean isValidInet6Address(String ip) {
		final String address = ip;
		InetAddressValidator validator = InetAddressValidator.getInstance();
		return validator.isValidInet6Address(address) ? Boolean.TRUE : Boolean.FALSE;
	}

	/**
	 * getDatabaseNameFromMetaData() method will perform operation on JsonObject
	 * inputJson object convert into databaseName and return response
	 * 
	 * @param JsonObject inputJson
	 * @methodName @getDatabaseNameFromMetaData
	 * @return String
	 * 
	 */
	private static String getDatabaseNameFromMetaData(JsonObject inputJson) {
		String databaseName = StringUtils.EMPTY;
		JsonObject resourceJSON = validateKeyExistance(inputJson, ApplicationConstants.RESOURCE);
		if (resourceJSON.entrySet().isEmpty() || !resourceJSON.has(ApplicationConstants.LABELS)) {
			return databaseName;
		}

		JsonObject labels = resourceJSON.get(ApplicationConstants.LABELS).getAsJsonObject();
		if (!labels.has(ApplicationConstants.DATASET_ID)) {
			return databaseName;
		}
		databaseName = CommonUtils.convertIntoString(labels.get(ApplicationConstants.DATASET_ID));
		return databaseName;
	}

	/**
	 * getEventQueryFromMetaData() method will perform operation on JsonObject
	 * serviceDataJson object into queryStatement and return response
	 * 
	 * @param JsonObject metaDataJson
	 * @methodName @getEventQueryFromMetaData
	 * @return String
	 * 
	 */
	private static String getEventQueryFromMetaData(JsonObject metaDataJson) {
		String query = StringUtils.EMPTY;
		if (metaDataJson.has(ApplicationConstants.JOB_INSERTION)) {
			query = getBigQueryAuditMetadataForJobEvent(
					metaDataJson.get(ApplicationConstants.JOB_INSERTION).getAsJsonObject());
		}
		if (StringUtils.isAllEmpty(query) && metaDataJson.has(ApplicationConstants.JOB_CHANGE)) {
			query = getBigQueryAuditMetadataForJobEvent(
					metaDataJson.get(ApplicationConstants.JOB_CHANGE).getAsJsonObject());
		}
		if (StringUtils.isAllEmpty(query) && metaDataJson.has(ApplicationConstants.TABLE_CREATION)) {
			query = getBigQueryAuditMetadataForTableEvent(
					metaDataJson.get(ApplicationConstants.TABLE_CREATION).getAsJsonObject());
		}
		if (StringUtils.isAllEmpty(query) && metaDataJson.has(ApplicationConstants.TABLE_CHANGE)) {
			query = getBigQueryAuditMetadataForTableEvent(
					metaDataJson.get(ApplicationConstants.TABLE_CHANGE).getAsJsonObject());
		}
		return query;
	}

	/**
	 * getBigQueryAuditMetadataForJobEvent() method will perform operation on
	 * JsonObject jobEvent object into queryStatement and return response
	 * 
	 * @param JsonObject jobEvent
	 * @methodName @getBigQueryAuditMetadataForJobEvent
	 * @return String
	 * 
	 */

	private static String getBigQueryAuditMetadataForJobEvent(JsonObject jobEvent) {
		JsonObject queryConfigJsonObject = null;
		String query = StringUtils.EMPTY;
		JsonObject jobJsonObject = jobEvent.has(ApplicationConstants.JOB)
				? jobEvent.get(ApplicationConstants.JOB).getAsJsonObject()
				: new JsonObject();
		JsonObject jobConfigJsonObject = new JsonObject();
		if (!jobJsonObject.entrySet().isEmpty() && jobJsonObject.has(ApplicationConstants.JOB_CONFIG)) {
			jobConfigJsonObject = jobJsonObject.get(ApplicationConstants.JOB_CONFIG).getAsJsonObject();
		}

		if (jobConfigJsonObject.has(ApplicationConstants.TYPE)) {
			if (jobConfigJsonObject.get(ApplicationConstants.TYPE).getAsString().equals(ApplicationConstants.IMPORT)) {
				if (jobConfigJsonObject.has(ApplicationConstants.LOAD_CONFIG)) {
					JsonObject loadConfigJsonObject = jobConfigJsonObject.get(ApplicationConstants.LOAD_CONFIG)
							.getAsJsonObject();
					if (loadConfigJsonObject.has(ApplicationConstants.DESTINATION_TABLE)) {
						String destinationTableStr = loadConfigJsonObject.get(ApplicationConstants.DESTINATION_TABLE)
								.getAsString();
						String datasetName = destinationTableStr.split("/")[3];
						String tableName = destinationTableStr.substring(destinationTableStr.lastIndexOf("/"))
								.replace("/", "");
						query = ApplicationConstants.CREATE_TABlE + " " + datasetName + "." + tableName;
					}
				}

			}
		}
		if (jobConfigJsonObject.has(ApplicationConstants.QUERY_CONFIG)) {
			queryConfigJsonObject = jobConfigJsonObject.get(ApplicationConstants.QUERY_CONFIG).getAsJsonObject();
			if (!queryConfigJsonObject.entrySet().isEmpty() && queryConfigJsonObject.has(ApplicationConstants.QUERY)) {
				query = CommonUtils.convertIntoString(queryConfigJsonObject.get(ApplicationConstants.QUERY));
			}
		}

		return query;
	}

	/**
	 * getBigQueryAuditMetadataForTableEvent() method will perform operation on
	 * JsonObject tableEvent object into queryStatement and return response
	 * 
	 * @param JsonObject tableEvent
	 * @methodName @getBigQueryAuditMetadataForTableEvent
	 * @return String
	 * 
	 */
	private static String getBigQueryAuditMetadataForTableEvent(JsonObject tableEvent) {
		String query = StringUtils.EMPTY;
		if (tableEvent.entrySet().isEmpty() || !tableEvent.has(ApplicationConstants.TABLE)) {
			return query;
		}
		JsonObject tableJson = tableEvent.get(ApplicationConstants.TABLE).getAsJsonObject();
		if (tableJson.entrySet().isEmpty() || !tableJson.has(ApplicationConstants.VIEW)) {
			return query;
		}
		JsonObject viewJson = tableJson.get(ApplicationConstants.VIEW).getAsJsonObject();
		if (viewJson.entrySet().isEmpty() || !viewJson.has(ApplicationConstants.QUERY)) {
			return query;
		}
		return CommonUtils.convertIntoString(viewJson.get(ApplicationConstants.QUERY));
	}

	/**
	 * getExceptionCodeAndMessage() method will perform operation on JsonObject
	 * protoPayload and String as a key object into Exception and code and return
	 * response
	 * 
	 * @param JsonObject protoPayload
	 * @param String     key
	 * @methodName @getExceptionCodeAndMessage
	 * @return String
	 * 
	 */
	private static String getExceptionCodeAndMessage(JsonObject protoPayload) {
		JsonObject status = validateKeyExistance(protoPayload, ApplicationConstants.STATUS);
		if (status.entrySet().isEmpty()) {
			return StringUtils.EMPTY;
		}
		return CommonUtils.convertIntoString(status.get(ApplicationConstants.MESSAGE));

	}

	/**
	 * validateKeyExistance() method will perform operation on JsonObject jsonObject
	 * and String as a key object into JsonObject and return response
	 * 
	 * @param JsonObject protoPayload
	 * @param String     key
	 * @methodName @validateKeyExistance
	 * @return String
	 * 
	 */
	private static JsonObject validateKeyExistance(JsonObject jsonObject, String key) {
		if (!jsonObject.has(key)) {
			return jsonObject;
		}
		return jsonObject.get(key).getAsJsonObject();
	}

	/**
	 * getFieldValueByKey() method will perform operation on JsonObject jsonObject
	 * and String as a key object into JsonObject and return response
	 * 
	 * @param JsonObject jsonObject
	 * @param String     key
	 * @methodName @getFieldValueByKey
	 * @return String
	 * 
	 */
	private static String getFieldValueByKey(JsonObject jsonObject, String key) {
		return CommonUtils.convertIntoString(jsonObject.get(key));
	}

	/**
	 * Method to generate HashCode for session using the CallerIp, Caller Port,
	 * AppUserName, DbName
	 * 
	 * @param callerIp
	 * @param callerPort
	 * @param appUserName
	 * @param dbName
	 * @return
	 */
	private static String getSessionHash(String callerIp, int callerPort, String appUserName, String dbName) {
		callerIp = callerIp != null ? callerIp.toString().trim() : StringUtils.EMPTY;
		appUserName = appUserName != null ? appUserName.toString().trim() : StringUtils.EMPTY;
		dbName = dbName != null ? dbName.toString().trim() : StringUtils.EMPTY;
		return String.valueOf((callerIp + String.valueOf(callerPort) + dbName + appUserName).hashCode());
	}

	/**
	 * getErrorMessagee() method will perform operation on JsonObject metaDataJson
	 * object into query and return response
	 * 
	 * @param JsonObject metaDataJson
	 * @methodName @getErrorMessage
	 * @return String
	 * 
	 */
	private static String getErrorMessage(JsonObject metaDataJson) {
		String query = StringUtils.EMPTY;
		if (metaDataJson.has(ApplicationConstants.JOB_INSERTION)) {
			query = getMessage(metaDataJson.get(ApplicationConstants.JOB_INSERTION).getAsJsonObject());
		}
		if (StringUtils.isAllEmpty(query) && metaDataJson.has(ApplicationConstants.JOB_CHANGE)) {
			query = getMessage(metaDataJson.get(ApplicationConstants.JOB_CHANGE).getAsJsonObject());
		}

		return query;
	}

	/**
	 * getMessage() method will perform operation on JsonObject jobEvent object into
	 * errorMessage and return response
	 * 
	 * @param JsonObject jobEvent
	 * @methodName @getMessage
	 * @return String
	 * 
	 */
	private static String getMessage(JsonObject jobEvent) {
		String errormessage = StringUtils.EMPTY;
		JsonObject jobJsonObject = jobEvent.has(ApplicationConstants.JOB)
				? jobEvent.get(ApplicationConstants.JOB).getAsJsonObject()
				: new JsonObject();
		JsonObject jobConfigJsonObject = new JsonObject();
		if (!jobJsonObject.entrySet().isEmpty() && jobJsonObject.has(ApplicationConstants.JOB_STATUS)) {
			jobConfigJsonObject = jobJsonObject.get(ApplicationConstants.JOB_STATUS).getAsJsonObject();
		}
		if (jobConfigJsonObject.entrySet().isEmpty() && !jobConfigJsonObject.has(ApplicationConstants.ERROR_RESULT)) {
			return errormessage;
		}
		JsonObject queryConfigJsonObject = jobConfigJsonObject.get(ApplicationConstants.ERROR_RESULT).getAsJsonObject();
		if (queryConfigJsonObject.entrySet().isEmpty() && !queryConfigJsonObject.has(ApplicationConstants.MESSAGE)) {
			return errormessage;
		}
		errormessage = queryConfigJsonObject.get(ApplicationConstants.MESSAGE).getAsString();
		return errormessage;
	}

	/**
	 * redactedHelper() method will perform operation on string and replacing the
	 * with ? for sensitive data
	 * 
	 * 
	 * @param String sqlQuery
	 * @methodName @redactedHelper
	 * @return String
	 * 
	 */
	private static String redactedHelper(String sqlQuery) {
		final Matcher matcher = redactedRegexpattern.matcher(sqlQuery);
		return matcher.replaceAll(ApplicationConstants.SUBSTITUTE_WITH_QUESTION_MARK);
	}

	/**
	 * getEventQueryFromMetaDataForUl() method will perform operation on JsonObject
	 * metaDataJson object into queryStatement and return response
	 * 
	 * @param JsonObject metaDataJson
	 * @methodName @getEventQueryFromMetaDataForUl
	 * @return String
	 * 
	 */
	private static String getEventQueryFromMetaDataForUl(JsonObject metaDataJson) {
		String query = StringUtils.EMPTY;
		if (metaDataJson.has(ApplicationConstants.DATASET_CREATION)) {
			query = getBigQueryAuditMetadataForReason(
					metaDataJson.get(ApplicationConstants.DATASET_CREATION).getAsJsonObject());
		}
		if (StringUtils.isAllEmpty(query) && metaDataJson.has(ApplicationConstants.DATASET_DELETION)) {
			query = getBigQueryAuditMetadataForReason(
					metaDataJson.get(ApplicationConstants.DATASET_DELETION).getAsJsonObject());
		}
		if (StringUtils.isAllEmpty(query) && metaDataJson.has(ApplicationConstants.TABLE_CREATION)) {
			query = getBigQueryAuditMetadataForReason(
					metaDataJson.get(ApplicationConstants.TABLE_CREATION).getAsJsonObject());
		}
		if (StringUtils.isAllEmpty(query) && metaDataJson.has(ApplicationConstants.TABLE_DELETION)) {
			query = getBigQueryAuditMetadataForReason(
					metaDataJson.get(ApplicationConstants.TABLE_DELETION).getAsJsonObject());
		}
		return query;
	}

	/**
	 * getBigQueryAuditMetadataForReason() method will perform operation on
	 * JsonObject metadatajson object into queryStatement and return response
	 * 
	 * @param JsonObject metadatajson
	 * @methodName @getBigQueryAuditMetadataForReason
	 * @return String
	 * 
	 */
	private static String getBigQueryAuditMetadataForReason(JsonObject metadatajson) {
		String uireason = StringUtils.EMPTY;
		uireason = CommonUtils.convertIntoString(metadatajson.get(ApplicationConstants.REASON));
		return uireason;
	}

	/**
	 * getResourceName() method will perform operation on JsonObject inputJson
	 * object convert into appUserName and return response
	 * 
	 * @param JsonObject protoPayloadJsonObject
	 * @methodName @getResourceName
	 * @return String
	 * 
	 */
	private static String getResourceName(JsonObject protoPayloadJsonObject) {
		String resourceName = StringUtils.EMPTY;
		resourceName = CommonUtils.convertIntoString(protoPayloadJsonObject.get(ApplicationConstants.RESOURCE_NAME));
		return resourceName;
	}

}
