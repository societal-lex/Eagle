/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.serviceimpl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.TransactionWork;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.types.Relationship;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.exception.BadRequestException;
import com.infosys.lexauthoringservices.model.Response;
import com.infosys.lexauthoringservices.model.UpdateMetaRequest;
import com.infosys.lexauthoringservices.service.FeatureAccessControlService;
import com.infosys.lexauthoringservices.service.GraphService;
import com.infosys.lexauthoringservices.util.LexConstants;
import com.infosys.lexauthoringservices.util.LexLogger;
import com.infosys.lexauthoringservices.util.LexServerProperties;

@Service
public class FeatureAccessControlServiceImpl  implements FeatureAccessControlService{

	private static AtomicInteger atomicInteger = new AtomicInteger();
	
	private static List<String> masterFeatureLevel = Arrays.asList("Feature Group","Feature","API"); 
	
	@Autowired
	Driver neo4jDriver;
	
	@Autowired
	private LexLogger logger;
	
	@Autowired
	LexServerProperties lexServerProps;
	
	@Autowired
	GraphService graphService;
	
	public static SimpleDateFormat inputFormatterDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	
	@Override
	public List<Map<String,Object>> fetchAllData(String rootOrg, String org) throws Exception {
		List<String> all_ids = new ArrayList<>(); 
		Response response = new Response();
		Session session = neo4jDriver.session();
		List<Map<String,Object>> allFeatureNodes = new ArrayList<>();
		all_ids = session.readTransaction(new TransactionWork<List<String>>(){
			@Override
			public List<String> execute(Transaction arg0) {
				return getAllIds(rootOrg,arg0);
			}
		});
		
		for(String identifier:all_ids) {
			Map<String,Object> featureMap = getFeature(rootOrg, identifier);
			allFeatureNodes.add(featureMap);
		}
		return allFeatureNodes;
	}
	
	private List<String> getAllIds(String rootOrg, Transaction tx) {
		String query = "match(n:Feature_" + rootOrg + ") where n.featureLevel='Feature Group' return n.identifier"; 
		StatementResult statementResult = tx.run(query);
		List<String> allIds = new ArrayList<>();
		List<Record> records = statementResult.list();
		for(Record rec: records) {
			allIds.add(rec.get("n.identifier").toString().replace("\"", ""));
		}
		return allIds;
	}
	
	
	@Override
	public Map<String, Object> getFeature(String rootOrg, String identifier) throws BadRequestException,Exception {
		Session session = neo4jDriver.session();
		Map<String,Object> hierarchyMap = new HashMap<>();
		hierarchyMap = session.readTransaction(new TransactionWork<Map<String,Object>>() {
			@Override
			public Map<String,Object> execute(Transaction tx){
				return getFeatureHierarchyFromNeo4j(identifier,rootOrg,tx,false);
			}
		});
		parserIterator(hierarchyMap);
		return hierarchyMap;
	}
	
	private String compareWithMasterAndGetParent(Map<String, Object> featureMap, Map<String, Object> masterMap) {
		Queue<Map<String,Object>> parentObjs = new LinkedList<>();
		parentObjs.add(masterMap);
		while(!parentObjs.isEmpty()) {
			Map<String,Object> parent = parentObjs.poll();
			String parName = (String) parent.get("name");
			String parentLevel = (String) parent.get("featureLevel");
			if(parName.equals(featureMap.get("name")) && parentLevel.equals(featureMap.get("featureLevel"))) {
				return parName;
			}
			List<Map<String,Object>> subFeatures = (List<Map<String, Object>>) parent.get(LexConstants.SUB_FEATURE);
			if(subFeatures.size()>0) {
				for(Map<String, Object> subF : subFeatures) {
					String chName = (String) subF.get("name");
					String chLevel = (String) subF.get("featureLevel");
					if(chName.equals(featureMap.get("name")) && chLevel.equals(featureMap.get("featureLevel"))) {
						return parName;
					}
				}
			}
			parent.put(LexConstants.SUB_FEATURE, subFeatures);
			parentObjs.addAll(subFeatures);
		}
			throw new BadRequestException("No such feature node can be created, No entry in masterGraph for dimension: " + featureMap.get("dimension")+ ", name: " + featureMap.get("name") + ", featureLevel: " + featureMap.get("featureLevel"));	
	}

	@SuppressWarnings("unchecked")
	private void parserIterator(Map<String, Object> hierarchyMap) throws Exception {
		Queue<Map<String,Object>> parentObjs = new LinkedList<>();
		hierarchyMap = (Map<String, Object>) hierarchyMap.get("feature");
		parentObjs.add(hierarchyMap);
		while(!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			List<Map<String, Object>> childrenList = (ArrayList) parent.get(LexConstants.SUB_FEATURE);
			try {
				mapParser(parent, true);
			} catch (Exception e) {
				e.printStackTrace();
				throw new Exception(e);
			}
			parent.put(LexConstants.SUB_FEATURE, childrenList);
			parentObjs.addAll(childrenList);
		}
		
	}

	@SuppressWarnings("unchecked")
	protected Map<String, Object> getFeatureHierarchyFromNeo4j(String identifier, String rootOrg, Transaction tx,boolean fetchMaster) {
		String query = null;
		if(fetchMaster) {
			query = "match(n:Feature{dimension:'" + identifier  + "'}) where n.featureLevel='Feature Group' with n optional match(n)-[r:Has_Sub_Feature*]->(s:Feature) return n,r,s";
		}
		else {
		query = "match(n:Feature_" + rootOrg + "{identifier:'" + identifier  + "'}) with n optional match(n)-[r:Has_Sub_Feature*]->(s:Feature_" + rootOrg + ") where n.rootOrg='" + rootOrg + "' return n,r,s";
		}
		StatementResult statementResult = tx.run(query);
		List<Record> records = statementResult.list();
		Map<String, Map<String, Object>> idToNodeMapping = new HashMap<>();
		Map<String, String> relationToLexIdMap = new HashMap<>();
		Map<String, Object> hierarchyMap = new HashMap<>();
		Map<String, Object> visitedMap = new HashMap<>();
		String sourceId = null;
		String destinationId = null;
		
		for(Record rec : records) {
			if (rec.get("r").isNull() && rec.get("s").isNull()) {
				Map<String, Object> resourceMap = new HashMap<>();
				org.neo4j.driver.v1.types.Node startNode = rec.get("n").asNode();
				resourceMap = startNode.asMap();
				Map<String, Object> newResourceMap = new HashMap<>();
				newResourceMap = new HashMap<>(resourceMap);
				List<Map<String, Object>> childrenList = new ArrayList<>();
				newResourceMap.put(LexConstants.SUB_FEATURE, childrenList);
				Map<String,Object> resultMap = new HashMap<>();
				resultMap.put("feature", newResourceMap);
				return resultMap;
			}
			
			List<Object> relations = (rec.get("r")).asList();
			org.neo4j.driver.v1.types.Node startNode = rec.get("n").asNode();
			org.neo4j.driver.v1.types.Node endNode = rec.get("s").asNode();

			if(fetchMaster) {
				sourceId = startNode.get(LexConstants.NAME).toString().replace("\"", "");
				destinationId = endNode.get(LexConstants.NAME).toString().replace("\"", "");
			}
			else {
				sourceId = startNode.get(LexConstants.IDENTIFIER).toString().replace("\"", "");
				destinationId = endNode.get(LexConstants.IDENTIFIER).toString().replace("\"", "");
			}
			
			idToNodeMapping.put(sourceId, startNode.asMap());
			idToNodeMapping.put(destinationId, endNode.asMap());
			
			String immediateParentId = sourceId;
			
			for (Object relation : relations) {
				if (!relationToLexIdMap.containsKey(relation.toString())) {
					relationToLexIdMap.put(relation.toString(), destinationId);
					Map<String, Object> parentMap = new HashMap<>();
					// called only once for that identifier whose hierarchy is
					// begin fetched
					if (!visitedMap.containsKey(immediateParentId)) {
						parentMap.putAll(idToNodeMapping.get(immediateParentId));
						hierarchyMap.put("feature", parentMap);
						visitedMap.put(immediateParentId, parentMap);
					} else {
						parentMap = (Map<String, Object>) visitedMap.get(immediateParentId);
					}
					List<Map<String, Object>> children = new ArrayList<>();
					if (parentMap.containsKey(LexConstants.SUB_FEATURE)) {
						children = (List<Map<String, Object>>) parentMap.get(LexConstants.SUB_FEATURE);
					}
					Map<String, Object> child = new HashMap<>();
					visitedMap.put(destinationId, child);
					// child.put("id", destinationId);
					child.putAll(idToNodeMapping.get(destinationId));
//					child.put(LexConstants.INDEX, ((Relationship) relation).asMap().get(LexConstants.INDEX));
					child.put(LexConstants.SUB_FEATURE, new ArrayList<>());
					children.add(child);
					parentMap.put(LexConstants.SUB_FEATURE, children);
				} else {
					immediateParentId = relationToLexIdMap.get(relation.toString());
				}
			}
			
		}
		return hierarchyMap;
	}

	@SuppressWarnings("unchecked")
	private Map<String,Object> mapParser(Map<String, Object> newResourceMap,boolean isMap) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Map<String,Object> updatedMap = newResourceMap;
		if(isMap) {
			try {
				if(updatedMap.get("roles")!=null) {
					String strRoles = updatedMap.get("roles").toString();
					Map<String,Object> roleMap = mapper.readValue(strRoles, HashMap.class);
					updatedMap.put("roles", roleMap);
				}
				if(updatedMap.get("group")!=null) {
					String strRoles = updatedMap.get("group").toString();
					Map<String,Object> roleMap = mapper.readValue(strRoles, HashMap.class);
					updatedMap.put("group", roleMap);
				}
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}
		else {
			
		}
		return updatedMap;
		
	}

	@SuppressWarnings("unchecked")
	@Override
	public String createFeatureNode(String rootOrg, String org, Map<String, Object> requestBody) throws BadRequestException,Exception {
		boolean available = false;
		boolean parentFlag = false;
		boolean enable =false;
		String featureName = null;
		String featureLevel = null;
		String dimension = null;
		Map<String,Object> rolesMap = new HashMap<>();
		Map<String,Object> groupMap = new HashMap<>();
		Map<String,Object> featureMap = new HashMap<>();
		Map<String,Object> boolMap = new HashMap<>();
		Map<String,Object> masterMap = new HashMap<>();
		String parentId = null;
		try {
			available = (boolean) requestBody.get("available");
			enable = (boolean) requestBody.get("enable");
			rolesMap = (Map<String, Object>) requestBody.get("roles");
			groupMap = (Map<String, Object>) requestBody.get("group");
			featureName = (String) requestBody.get("name");
			dimension = (String) requestBody.get("dimension");
			featureLevel = (String) requestBody.get("featureLevel");
			featureMap.put("featureLevel", featureLevel);
			featureMap.put("dimension", dimension);
			featureMap.put("available", available);
			featureMap.put("enable", enable);
			featureMap.put("roles",rolesMap );
			featureMap.put("group",groupMap );
			featureMap.put("name",featureName);
			featureMap.put("rootOrg", rootOrg);
			featureMap.put("featureOrg",org);
		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e);
		}
		Session session = neo4jDriver.session();
		final String masterdim = dimension;
		masterMap = session.readTransaction(new TransactionWork<Map<String,Object>>() {
			@Override
			public Map<String,Object> execute(Transaction tx){
				return getFeatureHierarchyFromNeo4j(masterdim,null,tx,true);
			}
		});
		
		System.out.println(masterMap);
		System.out.println(masterMap.size());
		if(masterMap.isEmpty()) {
			throw new BadRequestException("No such feature node can be created, No entry in masterGraph for dimension: " + featureMap.get("dimension")+ ", name: " + featureMap.get("name") + ", featureLevel: " + featureMap.get("featureLevel"));
		}
		masterMap = (Map<String, Object>) masterMap.get("feature");
		if(masterMap.size()<1) {
			throw new BadRequestException("No such feature node can be created, No entry in masterGraph for dimension: " + featureMap.get("dimension")+ ", name: " + featureMap.get("name") + ", featureLevel: " + featureMap.get("featureLevel"));
		}
		String parentName = compareWithMasterAndGetParent(featureMap,masterMap);
		System.out.println(parentName);
		
		
		if(rolesMap==null||rolesMap.isEmpty()||groupMap==null||groupMap.isEmpty()||featureName==null||featureName.isEmpty()||featureLevel==null||featureLevel.isEmpty()||dimension==null||dimension.isEmpty()) {
			throw new BadRequestException("Invalid Request Meta");
		}
		
		if(!masterFeatureLevel.contains(featureLevel)) {
			throw new BadRequestException("Invalid Feature Level " + featureLevel + "Accepted Values are "  + masterFeatureLevel);
		}
		
		String identifier = lexServerProps.getFeatureIdPrefix() + getUniqueIdFromTimestamp(0);
		//Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();
		featureMap.put(LexConstants.IDENTIFIER,identifier);
		
		if(!featureLevel.equals(masterFeatureLevel.get(0))) {
			boolMap = checkParentFeature(featureMap,parentName);
			System.out.println(boolMap);
			parentFlag=true;
			if(boolMap.size()<1) {
				throw new BadRequestException("Parent Node Does not exist for given dimension");
			}
			parentId = (String) boolMap.get("parentIdentifier");
			featureMap.put("available", boolMap.get("available"));
			featureMap.put("enable", boolMap.get("enable"));
			checkIfNodeExists(featureMap);
		}
		else {
			checkIfNodeExists(featureMap);
		}
		
		try {
			graphService.createFeatureNode(rootOrg, featureMap, transaction);
			transaction.commitAsync().toCompletableFuture().get();
			logger.info("Content node created successfully for identifier : " + featureMap.get(LexConstants.IDENTIFIER));
			if(parentFlag) {
			if(featureMap.get("featureLevel").equals(masterFeatureLevel.get(2))) {
				parentId = (String) boolMap.get("parentIdentifier");
			}
			Transaction relationTransaction = session.beginTransaction();
			createParentChildRelation(rootOrg,identifier,parentId,relationTransaction);
			}
			return featureMap.get(LexConstants.IDENTIFIER).toString();
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			logger.info("Content node creation failed for identifier : " + featureMap.get(LexConstants.IDENTIFIER));
			throw e;
		} finally {
			session.close();
		}
	}

	

	private void checkIfNodeExists(Map<String, Object> featureMap) {
		String dimension = (String) featureMap.get("dimension");
		String rootOrg = (String) featureMap.get(LexConstants.ROOT_ORG);
		String name = (String) featureMap.get("name");
		String query = "match(n:Feature_" + rootOrg + ") where n.dimension='" + dimension + "' and n.name='" + name+ "' return n";
		Map<String,Object> parentMap = new HashMap<>();
		Session readSession = neo4jDriver.session();
		parentMap = readSession.readTransaction(new TransactionWork<Map<String,Object>>() {
			@Override
			public Map<String,Object> execute(Transaction tx){
				return runQueryToGetNodeFG(query,tx);
			}
		});
		if(parentMap.size()>0) {
			throw new BadRequestException("Node already exists for dimension: "+ dimension + ", rootOrg: " + rootOrg + ", level: " + featureMap.get("featureLevel") + ", name: "+ name);
		}
	}

	private void createParentChildRelation(String rootOrg,String identifier, String parentId, Transaction relationTransaction) throws InterruptedException, ExecutionException {
		String query = "match(n:Feature_" + rootOrg + "{identifier:'" +parentId + "'}),(n1:Feature_" + rootOrg + "{identifier:'" +identifier + "'}) merge(n)-[r:Has_Sub_Feature]->(n1)";
		try {
			StatementResult statementResult = relationTransaction.run(query);
			List<Record> records = statementResult.list();
			relationTransaction.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			relationTransaction.rollbackAsync().toCompletableFuture().get();
		}
	}

	private Map<String,Object> checkParentFeature(Map<String, Object> featureMap,String parentName) {
		String childFeatureLevel = (String) featureMap.get("featureLevel");
		int childIndex = masterFeatureLevel.indexOf(childFeatureLevel);
		int parentIndex = childIndex - 1;
		String rootOrg = (String) featureMap.get("rootOrg");
		String query = "match(n:Feature_" + rootOrg +") where n.featureLevel='" + masterFeatureLevel.get(parentIndex) + "' and n.dimension='" + featureMap.get("dimension") + "' and n.rootOrg='" + rootOrg + "' and n.name='" + parentName + "' return n";
		Session readSession = neo4jDriver.session();
		Map<String,Object> parentMap = new HashMap<>();
		Map<String,Object> resultMap = new HashMap<>();
		parentMap = readSession.readTransaction(new TransactionWork<Map<String,Object>>() {
			@Override
			public Map<String,Object> execute(Transaction tx){
				return runQueryToGetNode(query,tx);
			}
		});
		boolean parentAvailable = (boolean) parentMap.get("available");
		boolean parentEnable = (boolean) parentMap.get("enable");
		
		boolean featureAvailable = (boolean) featureMap.get("available");
		boolean featureEnable = (boolean) featureMap.get("enable");
		
		boolean resultAvailable = parentAvailable & featureAvailable;
		boolean resultEnable = parentEnable & featureEnable;
		String parentIdentifier = (String) parentMap.get("identifier");
		resultMap.put("available",resultAvailable);
		resultMap.put("enable",resultEnable);
		resultMap.put("parentIdentifier", parentIdentifier);
		resultMap.put("parentdimension", parentMap.get("dimension"));
		resultMap.put("parentFeatureLevel", parentMap.get("featureLevel"));
		return resultMap;
		
	}


	protected Map<String, Object> runQueryToGetNode(String query, Transaction tx) {
		logger.debug("Running Query : " + query);
		Map<String,Object> resultMap = new HashMap<>();
		StatementResult statementResult = tx.run(query);
		List<Record> records = statementResult.list();
		if(records.size()<=0) {
			throw new BadRequestException("No parent Node exists for passed dimension" );
		}
		for(Record rec : records) {
			resultMap = rec.get("n").asMap();
		}
		return resultMap;
	}
	
	protected Map<String, Object> runQueryToGetNodeFG(String query, Transaction tx) {
		logger.debug("Running Query : " + query);
		Map<String,Object> resultMap = new HashMap<>();
		StatementResult statementResult = tx.run(query);
		List<Record> records = statementResult.list();
		for(Record rec : records) {
			resultMap = rec.get("n").asMap();
		}
		return resultMap;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Response updateFeatureNode(String rootOrg, String org, Map<String, Object> requestBody) throws BadRequestException,Exception {
		boolean available = false;
		boolean enable = false;
		String featureLevel = null;
		String identifier = null;
		String dimension = null;
		Response response = new Response();
		boolean masterAvailability = false;
		boolean masterEnable = false;
		Map<String,Object> rolesMap = new HashMap<>();
		Map<String,Object> groupMap = new HashMap<>();
		Map<String,Object> boolMap = new HashMap<>();
		Map<String,Object> parentMap = new HashMap<>();
		List<Map<String,Object>> flatList = new ArrayList<>();
		try {
			identifier = (String) requestBody.get("identifier");
			available = (boolean) requestBody.get("available");
			enable = (boolean) requestBody.get("enable");
			rolesMap = (Map<String, Object>) requestBody.get("roles");
			groupMap = (Map<String, Object>) requestBody.get("group");
		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e);
		}
		
		if(rolesMap==null||rolesMap.isEmpty()||groupMap==null||groupMap.isEmpty()) {
			throw new BadRequestException("Invalid Request Meta");
		}
		
		
		Map<String,Object> hierarchyMap = getFeature(rootOrg, identifier);
		hierarchyMap = (Map<String, Object>) hierarchyMap.get("feature");
		hierarchyMap.put("roles", rolesMap);
		hierarchyMap.put("group", groupMap);
		hierarchyMap.put("rootOrg", rootOrg);
		hierarchyMap.put("featureOrg", org);
		hierarchyMap.put("identifier",identifier);
		hierarchyMap.put("available", available);
		hierarchyMap.put("enable", enable);
		dimension = (String) hierarchyMap.get("dimension");
		featureLevel = (String) hierarchyMap.get("featureLevel");
		
		if(hierarchyMap.size()<1) {
			throw new BadRequestException("Node does not exist cannot update");
		}
		Session session = neo4jDriver.session();
		final String masterdim = dimension;
		Map<String,Object >masterMap = session.readTransaction(new TransactionWork<Map<String,Object>>() {
			@Override
			public Map<String,Object> execute(Transaction tx){
				return getFeatureHierarchyFromNeo4j(masterdim,null,tx,true);
			}
		});
		
		masterMap = (Map<String, Object>) masterMap.get("feature");
		String parentName = compareWithMasterAndGetParent(hierarchyMap,masterMap);
		
		if(!featureLevel.equals(masterFeatureLevel.get(0))) {
			parentMap = checkParentFeature(hierarchyMap,parentName);
			hierarchyMap.put("available", parentMap.get("available"));
			hierarchyMap.put("enable", parentMap.get("enable"));
			masterAvailability = (boolean) parentMap.get("available");
			masterEnable = (boolean) parentMap.get("enable");
			flatList = percolateBool(hierarchyMap,masterAvailability,masterEnable);
		}
		else {
			masterAvailability = available;
			masterEnable = enable;
			hierarchyMap.put("available", masterAvailability);
			hierarchyMap.put("enable", masterEnable);
			flatList = percolateBool(hierarchyMap,masterAvailability,masterEnable);
		}
		List<UpdateMetaRequest> updateList = formUpdateRequests(flatList);
		
		Transaction tx = session.beginTransaction();
		try {
			graphService.updateFeatureNodesV2(rootOrg, updateList, tx);
			tx.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			tx.rollbackAsync().toCompletableFuture().get();
			e.printStackTrace();
			throw new Exception(e);
		}
		finally {
			session.close();
		}
		response.put("Message", "Operation Successful, updated node with id : " + identifier);
		return response;
	}

	private List<UpdateMetaRequest> formUpdateRequests(List<Map<String, Object>> flatList) {
		List<UpdateMetaRequest> updateList = new ArrayList<>();
		for(Map<String, Object> mapObj:flatList) {
			mapObj.remove(LexConstants.SUB_FEATURE);
			UpdateMetaRequest updateMapReq = new UpdateMetaRequest((String) mapObj.get(LexConstants.IDENTIFIER),mapObj);
			updateList.add(updateMapReq);
		}
		return updateList;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String,Object>> percolateBool(Map<String, Object> hierarchyMap, boolean masterAvailability, boolean masterEnable) {
		Queue<Map<String,Object>> parentObjs = new LinkedList<>();
		List<Map<String,Object>> flatList = new ArrayList<>();
		parentObjs.add(hierarchyMap);
		while(!parentObjs.isEmpty()) {
			Map<String,Object> parent = parentObjs.poll();
			List<Map<String,Object>> subFeatures = (List<Map<String, Object>>) parent.get(LexConstants.SUB_FEATURE);
			boolean childAvailable = (boolean) parent.get("available");
			boolean childEnable = (boolean) parent.get("enable");
			parent.put("available", childAvailable & masterAvailability);
			parent.put("enable", childEnable & masterEnable);
			flatList.add(parent);
			parent.put(LexConstants.SUB_FEATURE, subFeatures);
			parentObjs.addAll(subFeatures);
		}
		return flatList;
	}

	@Override
	public Response deleteFeatureNode(String rootOrg, String org, Map<String, Object> requestBody) throws Exception {
		String identifier = (String) requestBody.get(LexConstants.IDENTIFIER);
		Map<String,Object> hierarchyMap = getFeature(rootOrg,identifier);
		Response response = new Response();
		Set<String> all_ids = getAllIds(hierarchyMap);
		Session session = neo4jDriver.session();
		Transaction tx = session.beginTransaction();
		
		if(identifier==null || identifier.isEmpty() || rootOrg==null || rootOrg.isEmpty()) {
			throw new BadRequestException("Identifier is invalid");
		}
		
		try {
			graphService.deleteFeatureNodes(rootOrg, all_ids, tx);
			tx.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			tx.rollbackAsync().toCompletableFuture().get();
			e.printStackTrace();
			throw new Exception(e);
		}
		finally {
			session.close();
		}
		response.put("Message", "Deleted all Feature and sub-feature nodes with identifier : " + identifier);
		return response;
	}
	
	
	

	@SuppressWarnings("unchecked")
	private Set<String> getAllIds(Map<String, Object> hierarchyMap) {
		Queue<Map<String,Object>> parentObjs = new LinkedList<>();
		hierarchyMap = (Map<String, Object>) hierarchyMap.get("feature");
		parentObjs.add(hierarchyMap);
		Set<String> all_ids = new HashSet<>();
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			all_ids.add(parent.get(LexConstants.IDENTIFIER).toString());
			ArrayList childrenList = (ArrayList) parent.get(LexConstants.SUB_FEATURE);
			parentObjs.addAll(childrenList);
		}
		return all_ids;
	}

	private static String getUniqueIdFromTimestamp(int environmentId) {

		Random random = new Random();
		long env = (environmentId + random.nextInt(99999)) / 10000000;
		long uid = System.currentTimeMillis() + random.nextInt(999999);
		uid = uid << 13;
		return env + "" + uid + "" + atomicInteger.getAndIncrement();
	}

}
