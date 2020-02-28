/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.serviceimpl;

import java.io.IOException;
import java.net.URI;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.TransactionWork;
import org.neo4j.driver.v1.types.Relationship;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.exception.ApplicationLogicError;
import com.infosys.lexauthoringservices.exception.BadRequestException;
import com.infosys.lexauthoringservices.exception.ConflictErrorException;
import com.infosys.lexauthoringservices.exception.ResourceNotFoundException;
import com.infosys.lexauthoringservices.model.Response;
import com.infosys.lexauthoringservices.model.UpdateMetaRequest;
import com.infosys.lexauthoringservices.model.UpdateRelationRequest;
import com.infosys.lexauthoringservices.model.cassandra.ContentWorkFlowModel;
import com.infosys.lexauthoringservices.model.cassandra.User;
import com.infosys.lexauthoringservices.model.neo4j.ContentNode;
import com.infosys.lexauthoringservices.model.neo4j.Relation;
import com.infosys.lexauthoringservices.repository.cassandra.bodhi.ContentWorkFlowRepository;
import com.infosys.lexauthoringservices.repository.cassandra.sunbird.UserRepository;
import com.infosys.lexauthoringservices.service.ContentCrudService;
import com.infosys.lexauthoringservices.service.GraphService;
import com.infosys.lexauthoringservices.service.ValidationsService;
import com.infosys.lexauthoringservices.util.GraphUtil;
import com.infosys.lexauthoringservices.util.LexConstants;
import com.infosys.lexauthoringservices.util.LexLogger;
import com.infosys.lexauthoringservices.util.LexServerProperties;
import com.rockymadden.stringmetric.similarity.RatcliffObershelpMetric;

@Service
public class ContentCrudServiceImpl implements ContentCrudService {

	private static AtomicInteger atomicInteger = new AtomicInteger();

	@Autowired
	GraphService graphService;

	@Autowired
	ValidationsService validationsService;

	@Autowired
	UserRepository userRepo;

	@Autowired
	ContentWorkFlowRepository contentWorkFlowRepo;

	@Autowired
	LexServerProperties lexServerProps;

	@Autowired
	KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	RestTemplate restTemplate;

	@Autowired
	Driver neo4jDriver;

	@Autowired
	private LexLogger logger;

	public static SimpleDateFormat inputFormatterDate = new SimpleDateFormat("yyyy-MM-dd");
	public static SimpleDateFormat inputFormatterDateTime = new SimpleDateFormat("yyyyMMdd'T'HHmmssZ");

	@Override
	@SuppressWarnings("unchecked")
	public void createContentNodeForMigration(String rootOrg, String org, Map<String, Object> requestMap)
			throws Exception {

		List<Map<String, Object>> contentMetas = (List<Map<String, Object>>) requestMap.get(LexConstants.CONTENT);

		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();

		try {
			graphService.createNodes(rootOrg, contentMetas, transaction);
			transaction.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			throw e;
		} finally {
			transaction.close();
			session.close();
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public String createContentNode(String rootOrg, String org, Map<String, Object> requestMap) throws Exception {

		Map<String, Object> contentMeta = (Map<String, Object>) requestMap.get(LexConstants.CONTENT);

		createOperationValidations(contentMeta);

		// generate unique id for content
		String identifier = lexServerProps.getContentIdPrefix() + getUniqueIdFromTimestamp(0);
		contentMeta.put(LexConstants.IDENTIFIER, identifier);

		logger.info("identifier generated for content " + identifier);

		// user validations
		User user = verifyUserV2(contentMeta);
		// populate all required fields for given rootOrg as null
		if (contentMeta.get(LexConstants.CONTENT_TYPE).equals(LexConstants.PLAYLIST)) {
			populateMetaForPlaylist(rootOrg, contentMeta, user);
			List<String> children = (List<String>) contentMeta.get(LexConstants.CHILDREN);
			contentMeta.remove(LexConstants.CHILDREN);
			identifier = createNode(rootOrg, contentMeta);
			createChildRelationsPlaylist(identifier, children, rootOrg);
		} else if (validateContentType(contentMeta)) {
			populateMetaForCreation(rootOrg, org, contentMeta, user);
			String orgLangId = getOriginalLangNode(rootOrg, contentMeta);
			createContentFolderInS3(rootOrg, org, identifier, contentMeta);
			identifier = createNode(rootOrg, contentMeta);
			if (!orgLangId.isEmpty() || orgLangId != null) {
				// TODO create translation relation
				createTranslationRelation(identifier, orgLangId, rootOrg);
			}
		} else {
			throw new BadRequestException("invalid contentType " + contentMeta.get(LexConstants.CONTENT_TYPE));
		}
		return identifier;
	}

	private void createTranslationRelation(String identifier, String orgLangId, String rootOrg)
			throws InterruptedException, ExecutionException {
		String query = "match(n{identifier:'" + identifier + "'}) where n:Shared or n:" + rootOrg
				+ " with n match (n1{identifier:'" + orgLangId + "'}) where n1:Shared or n1:" + rootOrg
				+ " with n,n1 merge (n)-[r:Is_Translation_Of]->(n1)";
		Session translationSession = neo4jDriver.session();
		Transaction tx = translationSession.beginTransaction();
		try {
			StatementResult res = tx.run(query);
			tx.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			tx.rollbackAsync().toCompletableFuture().get();
		} finally {
			translationSession.close();
		}
	}

	private String getOriginalLangNode(String rootOrg, Map<String, Object> contentMeta) {
		String locale = (String) contentMeta.get(LexConstants.LOCALE);
		String translationOf = (String) contentMeta.getOrDefault(LexConstants.TRANSLATION_OF, "");
		String orgLangId = null;
		if (locale == null || locale.isEmpty()) {
			throw new BadRequestException("locale cannot be null or empty");
		}
		if (!translationOf.isEmpty() || (translationOf != null)) {
			orgLangId = findOriginalLangContent(translationOf, rootOrg);
		}
		return orgLangId;
	}

	@Override
	public Map<String, Object> getContentNode(String rootOrg, String identifier) throws BadRequestException, Exception {

		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();

		if (identifier.contains(LexConstants.IMG_SUFFIX)) {
			identifier = identifier.substring(0, identifier.indexOf(LexConstants.IMG_SUFFIX));
		}
		try {
			ContentNode contentNode = getNodeFromDb(rootOrg, identifier, transaction);
			transaction.commitAsync().toCompletableFuture().get();
			return contentNode.getMetadata();
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			throw e;
		} finally {
			transaction.close();
			session.close();
		}
	}

	@Override
	public void updateContentNode(String rootOrg, String org, String identifier, Map<String, Object> requestMap)
			throws Exception {

		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();

		if (requestMap == null || requestMap.isEmpty())
			throw new BadRequestException("empty request body");

		if (identifier.contains(LexConstants.IMG_SUFFIX))
			identifier = identifier.substring(0, identifier.indexOf(LexConstants.IMG_SUFFIX));

		try {
			updateNode(rootOrg, identifier, requestMap, transaction);
			transaction.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			throw e;
		} finally {
			transaction.close();
			session.close();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void updateContentHierarchy(String rootOrg, String org, Map<String, Object> requestMap, String migration)
			throws Exception {

		if (!requestMap.containsKey(LexConstants.NODES_MODIFIED))
			throw new BadRequestException("Request body does not contain nodesModified");

		if (!requestMap.containsKey(LexConstants.HIERARCHY))
			throw new BadRequestException("Request body does not contain hierarchy");

		Map<String, Object> nodesModified = removeImageSuffixFromNodesModified(
				(Map<String, Object>) requestMap.get(LexConstants.NODES_MODIFIED));
		Map<String, Object> hierarchy = (Map<String, Object>) requestMap.get(LexConstants.HIERARCHY);

		nodesModified = removeImageSuffixFromNodesModified(nodesModified);
		hierarchy = removeImageNodeSuffixFromHierarchyModified(hierarchy);

		logger.info("Update Hierarchy api called for request body. \n nodesModified: " + nodesModified.toString()
				+ "\n hierarchy:" + hierarchy.toString());
		// fetching the root node
		String rootNodeIdentifier = getRootNode(rootOrg, nodesModified, hierarchy);

		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();

		try {
			updateNodes(rootOrg, nodesModified, transaction);
			updateHierarchy(rootOrg, hierarchy, transaction, migration);
			// recalculate duration for all non-live nodes in the hierarchy of the rootNode.
			// possible optimization do not re calculate duration if hierarchy does not
			// change or duration field is updated in the meta
			if (migration.toLowerCase().equals("no")) {
				reCalculateDuration(rootOrg, org, rootNodeIdentifier, transaction);
			}
			transaction.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			throw e;
		} finally {
			transaction.close();
			session.close();
		}
	}

	@SuppressWarnings("unchecked")
	private void updateNodes(String rootOrg, Map<String, Object> nodesModified, Transaction transaction)
			throws Exception {

		if (nodesModified == null || nodesModified.isEmpty()) {
			return;
		}

		List<String> identifiersToFetch = new ArrayList<>(nodesModified.keySet());
		identifiersToFetch.addAll(identifiersToFetch.stream().map(identifier -> identifier + LexConstants.IMG_SUFFIX)
				.collect(Collectors.toList()));

		Map<String, ContentNode> idToContentMapping = new HashMap<>();
		graphService.getNodesByUniqueIdV2(rootOrg, identifiersToFetch, transaction)
				.forEach(contentNode -> idToContentMapping.put(contentNode.getIdentifier(), contentNode));

		List<UpdateMetaRequest> updateMetaRequests = new ArrayList<>();
		List<ContentNode> imageNodesToBeCreated = new ArrayList<>();

		for (Map.Entry<String, Object> entry : nodesModified.entrySet()) {
			String identifier = entry.getKey();
			if (!idToContentMapping.containsKey(identifier)) {
				throw new ResourceNotFoundException("Content with identifier: " + entry.getKey() + " does not exist");
			}

			Map<String, Object> updateMap = (Map<String, Object>) entry.getValue();
			updateMap = (Map<String, Object>) updateMap.get(LexConstants.METADATA);
			updateOperationValidations(updateMap);

			Calendar lastUpdatedOn = Calendar.getInstance();
			updateMap.put(LexConstants.LAST_UPDATED, inputFormatterDateTime.format(lastUpdatedOn.getTime()));

			ContentNode contentNode = idToContentMapping.get(entry.getKey());

			if (contentNode.getMetadata().get(LexConstants.STATUS).equals(LexConstants.Status.Live.getStatus())) {
				String imageNodeIdentifier = identifier + LexConstants.IMG_SUFFIX;

				if (idToContentMapping.containsKey(imageNodeIdentifier)) {
					updateMetaRequests.add(new UpdateMetaRequest(imageNodeIdentifier, updateMap));
				} else {
					// create image node
					ContentNode imageNode = populateMetaForImageNodeCreation(contentNode, updateMap);
					idToContentMapping.put(imageNode.getIdentifier(), imageNode);
					// adding the image node as parent in the children of the original node.
					ensureHierarchyCorrectnessForMetaUpdate(idToContentMapping, imageNode);
					imageNodesToBeCreated.add(imageNode);
				}
			} else {
				updateMetaRequests.add(new UpdateMetaRequest(identifier, updateMap));
			}
		}

		List<Map<String, Object>> imageNodesMetas = imageNodesToBeCreated.stream()
				.map(imageNode -> imageNode.getMetadata()).collect(Collectors.toList());

		graphService.createNodes(rootOrg, imageNodesMetas, transaction);
		graphService.updateNodesV2(rootOrg, updateMetaRequests, transaction);
		graphService.createRelations(rootOrg,
				GraphUtil.createUpdateRelationRequestsForImageNodes(imageNodesToBeCreated), transaction);

	}

	@SuppressWarnings("unchecked")
	private void updateHierarchy(String rootOrg, Map<String, Object> hierarchy, Transaction transaction,
			String migration) throws Exception {

		if (hierarchy == null || hierarchy.isEmpty()) {
			return;
		}

		List<String> contentIdsToFetch = new ArrayList<>(hierarchy.keySet());
		contentIdsToFetch.addAll(contentIdsToFetch.stream().map(identifier -> identifier + LexConstants.IMG_SUFFIX)
				.collect(Collectors.toList()));
		getChildrenGettingAttached(contentIdsToFetch, hierarchy);

		Map<String, ContentNode> idToContentMapping = new HashMap<>();
		graphService.getNodesByUniqueIdV2(rootOrg, contentIdsToFetch, transaction)
				.forEach(contentNode -> idToContentMapping.put(contentNode.getIdentifier(), contentNode));

		List<UpdateRelationRequest> updateRelationRequests = new ArrayList<>();
		List<String> idsForChildrenDeletion = new ArrayList<>();
		List<UpdateMetaRequest> updateMetaRequests = new ArrayList<>();

		for (Map.Entry<String, Object> entry : hierarchy.entrySet()) {
			// original node not found
			if (!idToContentMapping.containsKey(entry.getKey())) {
				throw new ResourceNotFoundException("Content with identifier: " + entry.getKey() + " does not exist.");
			}
			ContentNode contentNodeToUpdate = findContentNodeToUpdate(idToContentMapping, entry);
			// content is in live and image node does not exist so not updating the
			// hierarchy
			if (contentNodeToUpdate == null) {
				continue;
			}
			// removing this particular id from parents of its children.
			ensureHierarchyCorrectnessForHierarchyUpdate(idToContentMapping, contentNodeToUpdate);
			idsForChildrenDeletion.add(contentNodeToUpdate.getIdentifier());

			// create the new Relations, image node will be in the same index
			List<String> children = (List<String>) ((Map<String, Object>) entry.getValue()).get(LexConstants.CHILDREN);

			int index = 0;
			for (String child : children) {
				if (!idToContentMapping.containsKey(child)) {
					throw new ResourceNotFoundException("Content with identifier: " + child + " does not exist.");
				}

				ContentNode childNode = idToContentMapping.get(child);
				if (!childNode.getMetadata().containsKey(LexConstants.IS_STAND_ALONE)) {
					childNode.getMetadata().put(LexConstants.IS_STAND_ALONE, true);
				}
				if ((boolean) childNode.getMetadata().get(LexConstants.IS_STAND_ALONE) == true) {
					Map<String, Object> updateMap = new HashMap<>();
					updateMap.put(LexConstants.IS_STAND_ALONE, false);
					updateMetaRequests.add(new UpdateMetaRequest(childNode.getIdentifier(), updateMap));
				}

				if (migration.toLowerCase().equals("no")) {
					checkLearningPathConstraints(contentNodeToUpdate, idToContentMapping.get(child));
				}
				checkContentSharingConstraints(contentNodeToUpdate, childNode);
				updateRelationRequests
						.add(new UpdateRelationRequest(contentNodeToUpdate.getIdentifier(), child, index));

				if (idToContentMapping.containsKey(child + LexConstants.IMG_SUFFIX)) {

					ContentNode childImageNode = idToContentMapping.get(child + LexConstants.IMG_SUFFIX);
					if (!childImageNode.getMetadata().containsKey(LexConstants.IS_STAND_ALONE)) {
						childImageNode.getMetadata().put(LexConstants.IS_STAND_ALONE, true);
					}
					if ((boolean) childImageNode.getMetadata().get(LexConstants.IS_STAND_ALONE) == true) {
						Map<String, Object> updateMap = new HashMap<>();
						updateMap.put(LexConstants.IS_STAND_ALONE, false);
						updateMetaRequests.add(new UpdateMetaRequest(childImageNode.getIdentifier(), updateMap));
					}

					checkContentSharingConstraints(contentNodeToUpdate,
							idToContentMapping.get(child + LexConstants.IMG_SUFFIX));
					updateRelationRequests.add(new UpdateRelationRequest(contentNodeToUpdate.getIdentifier(),
							child + LexConstants.IMG_SUFFIX, index));
				}
				index++;
			}
		}

		graphService.deleteChildren(rootOrg, idsForChildrenDeletion, transaction);
		graphService.updateNodesV2(rootOrg, updateMetaRequests, transaction);
		graphService.createRelations(rootOrg, updateRelationRequests, transaction);
	}

	private ContentNode findContentNodeToUpdate(Map<String, ContentNode> idToContentMapping,
			Map.Entry<String, Object> entry) {

		ContentNode contentNodeToUpdate = idToContentMapping.get(entry.getKey());
		if (contentNodeToUpdate.getMetadata().get(LexConstants.STATUS).equals(LexConstants.Status.Live.getStatus())) {
			String imageNodeIdentifier = entry.getKey() + LexConstants.IMG_SUFFIX;
			if (idToContentMapping.containsKey(imageNodeIdentifier)) {
				return contentNodeToUpdate;
			} else {
				return null;
			}
		}
		return contentNodeToUpdate;
	}

	@Override
	public Map<String, Object> getContentHierarchy(String identifier, String rootOrg, String org)
			throws BadRequestException, Exception {

		Session session = neo4jDriver.session();

		Map<String, Object> hierarchyMap = new HashMap<>();
		List<String> fields = new ArrayList<>();
		hierarchyMap = session.readTransaction(new TransactionWork<Map<String, Object>>() {
			@Override
			public Map<String, Object> execute(Transaction tx) {

				return getHierarchyFromNeo4j(identifier, rootOrg, tx, false, fields);
			}
		});

		hierachyForViewing(hierarchyMap);

		session.close();
		return hierarchyMap;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void contentDelete(String identifier, String authorEmail, String rootOrg, String userType) throws Exception {

		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();
		List<String> fields = new ArrayList<>();
		// fetching entire hierarchy for the given identifier.
		Map<String, Object> hierarchy = (Map<String, Object>) getHierarchyFromNeo4j(identifier, rootOrg, transaction,
				false, fields).get(LexConstants.CONTENT);

		Queue<Map<String, Object>> contentQueue = new LinkedList<>();
		contentQueue.add(hierarchy);

		boolean isFirstCall = true;
		List<String> idsToDelete = new ArrayList<>();

		// filtering out the content that can be deleted from the hierarchy.
		while (!contentQueue.isEmpty()) {

			Map<String, Object> contentMeta = contentQueue.poll();
			if (isDeletable(contentMeta, authorEmail, userType, isFirstCall)) {
				idsToDelete.add(contentMeta.get(LexConstants.IDENTIFIER).toString());
				List<Map<String, Object>> children = (List<Map<String, Object>>) contentMeta.get(LexConstants.CHILDREN);
				contentQueue.addAll(children);
				isFirstCall = false;
			}
		}

		try {
			graphService.deleteNodes(rootOrg, idsToDelete, transaction);
			transaction.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			throw e;
		} finally {
			transaction.close();
			session.close();
		}
	}

	@SuppressWarnings("unchecked")
	private boolean isDeletable(Map<String, Object> contentMeta, String authorEmail, String userType,
			boolean isFirstCall) {

		if (!userType.equals(LexConstants.ADMIN)
				|| (!userType.equals(LexConstants.ADMIN) && !isGivenAuthorsContent(contentMeta, authorEmail))) {
			return false;
		}

		if (!contentMeta.get(LexConstants.STATUS).toString().equals(LexConstants.DRAFT)) {
			return false;
		}

		if (isFirstCall && ((List<Map<String, Object>>) contentMeta.get(LexConstants.CHILDREN)).size() > 0) {
			return false;
		}

		if (!isFirstCall && ((List<Map<String, Object>>) contentMeta.get(LexConstants.CHILDREN)).size() > 1) {
			return false;
		}
		return true;
	}

//	@SuppressWarnings("unchecked")
//	private Response publishContent(Map<String, Object> contentMeta, String rootOrg) throws Exception {
//		Response response = new Response();
//		String identifier = contentMeta.get(LexConstants.IDENTIFIER).toString();
//		Session session = neo4jDriver.session();
//		Transaction transaction = session.beginTransaction();
//		Map<String, Object> kafkaMap = new HashMap<>();
//		List<String> listOfIds = new ArrayList<>();
//		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
//		parentObjs.add(contentMeta);
//		while (!parentObjs.isEmpty()) {
//			Map<String, Object> parent = parentObjs.poll();
//			List<Map<String, Object>> childrenList = (List<Map<String, Object>>) parent.get(LexConstants.CHILDREN);
//			parent.put(LexConstants.STATUS, "Processing");
//			listOfIds.add(parent.get(LexConstants.IDENTIFIER).toString());
//			for (Map<String, Object> child : childrenList) {
//				parentObjs.add(child);
//			}
//		}
//		listOfIds = listOfIds.stream().map(id -> "'" + id + "'").collect(Collectors.toList());
//		kafkaMap.put("topLevelContentId", identifier);
//		kafkaMap.put("contentIds", listOfIds);
//		kafkaMap.put("rootOrg", rootOrg);
//		ObjectMapper mapper = new ObjectMapper();
//
//		try {
//			String query = "unwind " + listOfIds + " as data match(n:" + rootOrg
//					+ "{identifier:data}) set n.status=\"Processing\" return n";
//			List<Record> records = transaction.run(query).list();
//			if (listOfIds.size() == records.size()) {
//				transaction.commitAsync().toCompletableFuture().get();
//				String kafkaMessage = mapper.writer().writeValueAsString(kafkaMap);
//				kafkaTemplate.send("dev.learning.graph.events", "Publish-Pipeline-Stage-1", kafkaMessage);
//				response.put("Message", "Operation Successful: Sent to Publish Pipeline");
//				return response;
//			} else {
//				throw new ApplicationLogicError("Something went wrong");
//			}
//		} catch (Exception e) {
//			transaction.rollbackAsync().toCompletableFuture().get();
//			throw e;
//		} finally {
//			session.close();
//		}
//	}

	@SuppressWarnings("unchecked")
	private boolean isGivenAuthorsContent(Map<String, Object> contentMeta, String authorEmail)
			throws BadRequestException {
		// TODO validate if authorEmail is valid UUID
		UUID tempObj = null;
		try {
			tempObj = UUID.fromString(authorEmail);
		} catch (ClassCastException | IllegalArgumentException e) {
			throw new BadRequestException("MUST BE A VALID UUID");
		} catch (Exception e) {
			throw new ApplicationLogicError("userId");
		}
		List<Map<String, Object>> creatorContacts = (List<Map<String, Object>>) contentMeta
				.get(LexConstants.CREATOR_CONTACTS);

		for (Map<String, Object> creatorContact : creatorContacts) {
			if (creatorContact.get(LexConstants.ID).equals(authorEmail)) {
				return true;
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getHierarchyFromNeo4j(String identifier, String rootOrg, Transaction tx,
			boolean fieldsPassed, List<String> fields) {

		// query for image node fetch
		String query = "match(n{identifier:'" + identifier + ".img'}) where n:Shared or n:" + rootOrg
				+ " with n optional match(n)-[r:Has_Sub_Content*]->(s) where s:Shared or s:" + rootOrg
				+ " return n,r,s";
		StatementResult statementResult = tx.run(query);
		List<Record> records = statementResult.list();
		for (Record recordimg : records) {
			// hierarchy fetched for resource.img
			Map<String, Object> resourceMap = new HashMap<>();
			if (recordimg.get("r").isNull() && recordimg.get("s").isNull()) {
				org.neo4j.driver.v1.types.Node startNode = recordimg.get("n").asNode();
				resourceMap = startNode.asMap();
				Map<String, Object> newResourceMap = new HashMap<>();
				if (fieldsPassed) {
					newResourceMap = fieldsRequired(fields, resourceMap);
				} else {
					newResourceMap = new HashMap<>(resourceMap);
				}
				List<Map<String, Object>> childrenList = new ArrayList<>();
				newResourceMap.put(LexConstants.CHILDREN, childrenList);
				return newResourceMap;
			}
		}
		if (records.size() == 0) {
			// query for node fetch //if img node does not exist
			query = "match(n{identifier:'" + identifier + "'}) where n:Shared or n:" + rootOrg
					+ " with n optional match(n)-[r:Has_Sub_Content*]->(s) where s:Shared or s:" + rootOrg
					+ " return n,r,s";
			statementResult = tx.run(query);
			records = statementResult.list();
			if (records == null || records.size() == 0) {
				if (records.size() == 0) {
					throw new BadRequestException("Identifier does not exist : " + identifier);
				}
			}
		}

		Map<String, Map<String, Object>> idToNodeMapping = new HashMap<>();
		Map<String, String> relationToLexIdMap = new HashMap<>();
		Map<String, Object> hierarchyMap = new HashMap<>();
		Map<String, Object> visitedMap = new HashMap<>();
		for (Record record : records) {
			// hierarchy fetched for resource
			if (record.get("r").isNull() && record.get("s").isNull()) {
				Map<String, Object> resourceMap = new HashMap<>();
				org.neo4j.driver.v1.types.Node startNode = record.get("n").asNode();
				resourceMap = startNode.asMap();
				Map<String, Object> newResourceMap = new HashMap<>();
				if (fieldsPassed) {
					newResourceMap = fieldsRequired(fields, resourceMap);
				} else {
					newResourceMap = new HashMap<>(resourceMap);
				}
				List<Map<String, Object>> childrenList = new ArrayList<>();
				newResourceMap.put(LexConstants.CHILDREN, childrenList);
				return newResourceMap;
			}

			List<Object> relations = (record.get("r")).asList();
			org.neo4j.driver.v1.types.Node startNode = record.get("n").asNode();
			org.neo4j.driver.v1.types.Node endNode = record.get("s").asNode();

			String sourceId = startNode.get(LexConstants.IDENTIFIER).toString().replace("\"", "");
			String destinationId = endNode.get(LexConstants.IDENTIFIER).toString().replace("\"", "");

			if (fieldsPassed) {
				Map<String, Object> sNodeMap = fieldsRequired(fields, startNode.asMap());
				Map<String, Object> eNodeMap = fieldsRequired(fields, endNode.asMap());
				idToNodeMapping.put(sourceId, sNodeMap);
				idToNodeMapping.put(destinationId, eNodeMap);
			} else {
				idToNodeMapping.put(sourceId, startNode.asMap());
				idToNodeMapping.put(destinationId, endNode.asMap());
			}

			String immediateParentId = sourceId;

			for (Object relation : relations) {
				if (!relationToLexIdMap.containsKey(relation.toString())) {
					relationToLexIdMap.put(relation.toString(), destinationId);
					Map<String, Object> parentMap = new HashMap<>();
					// called only once for that identifier whose hierarchy is
					// begin fetched
					if (!visitedMap.containsKey(immediateParentId)) {
						parentMap.putAll(idToNodeMapping.get(immediateParentId));
						hierarchyMap.put("content", parentMap);
						visitedMap.put(immediateParentId, parentMap);
					} else {
						parentMap = (Map<String, Object>) visitedMap.get(immediateParentId);
					}
					List<Map<String, Object>> children = new ArrayList<>();
					if (parentMap.containsKey(LexConstants.CHILDREN)) {
						children = (List<Map<String, Object>>) parentMap.get(LexConstants.CHILDREN);
					}
					Map<String, Object> child = new HashMap<>();
					visitedMap.put(destinationId, child);
					// child.put("id", destinationId);
					child.putAll(idToNodeMapping.get(destinationId));
					child.put(LexConstants.INDEX, ((Relationship) relation).asMap().get(LexConstants.INDEX));
					child.put(LexConstants.CHILDREN, new ArrayList<>());
					children.add(child);
					parentMap.put(LexConstants.CHILDREN, children);
				} else {
					immediateParentId = relationToLexIdMap.get(relation.toString());
				}
			}
		}
		return orderChildren(hierarchyMap);
	}

	public List<Map<String, Object>> getReverseHierarchyFromNeo4jForDurationUpdate(String identifier, String rootOrg,
			Transaction tx) {

		// query for image node fetch
		String query = "match(n{identifier:'" + identifier + ".img'}) where n:Shared or n:" + rootOrg
				+ " with n optional match(n)<-[r:Has_Sub_Content*]-(s) where s:Shared or s:" + rootOrg
				+ " return {identifier:s.identifier,duration:s.duration,size:s.size} as parentData";

		StatementResult statementResult = tx.run(query);
		List<Record> records = statementResult.list();

		if (null != records && records.size() > 0) {
			List<Map<String, Object>> parentsData = new ArrayList<>();
			for (Record recordImg : records) {
				Map<String, Object> parentData = recordImg.get("parentData").asMap();
				if (!parentData.isEmpty())
					parentsData.add(parentData);
			}
			return parentsData;
		}

		query = "match(n{identifier:'" + identifier + "'}) where n:Shared or n:" + rootOrg
				+ " with n optional match(n)<-[r:Has_Sub_Content*]-(s) where s:Shared or s:" + rootOrg
				+ " return {identifier:s.identifier,duration:s.duration} as parentData";

		statementResult = tx.run(query);
		records = statementResult.list();

		if (null != records && records.size() > 0) {
			List<Map<String, Object>> parentsData = new ArrayList<>();
			for (Record record : records) {
				Map<String, Object> parentData = record.get("parentData").asMap();
				if (!parentData.isEmpty())
					parentsData.add(parentData);
			}
			return parentsData;
		}

		return new ArrayList<>();
	}

	@Override
	public Response externalContentPublish(String identifier, Map<String, Object> requestBody)
			throws BadRequestException, Exception {
		String rootOrg = null;
		String org = null;
		String commentMessage;
		String actor;
		String appName;
		String appUrl;
		try {
			rootOrg = (String) requestBody.get(LexConstants.ROOT_ORG);
			if (rootOrg.isEmpty() || rootOrg == null) {
				throw new BadRequestException("rootOrg is Empty");
			}
			org = (String) requestBody.get(LexConstants.ORG);
			if (org.isEmpty() || org == null) {
				throw new BadRequestException("org is Empty");
			}
			commentMessage = (String) requestBody.get(LexConstants.COMMENT);
			if (commentMessage == null || commentMessage.isEmpty()) {
				throw new BadRequestException("commentMessage is Empty");
			}
			actor = (String) requestBody.get(LexConstants.ACTOR);
			if (actor.isEmpty() || actor == null) {
				throw new BadRequestException("actor is Empty");
			}
			appName = (String) requestBody.get(LexConstants.APPNAME);
			if (appName == null || appName.isEmpty()) {
				throw new BadRequestException("appName is Empty");
			}
			appUrl = (String) requestBody.get(LexConstants.APPURL);
			if (appUrl == null || appUrl.isEmpty()) {
				throw new BadRequestException("appUrl is Empty");
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e);
		}

		Session session = neo4jDriver.session();

		// transaction started
		Transaction transaction = session.beginTransaction();
		// returns complete contentMeta along with conflicting siblings
		Map<String, Object> contentMeta = getContentHierarchy(identifier, rootOrg, org);
		Response response = new Response();
		boolean isExternal = false;
		try {
			isExternal = (boolean) contentMeta.get(LexConstants.ISEXTERNAL);
		} catch (Exception e) {
			throw new BadRequestException("isExternal is corrupt not bool value");
		}

		if (!isExternal) {
			throw new BadRequestException("Content is not external");
		}
		// returns all contentMaps in a flat list
		List<Map<String, Object>> listOfContentMetas = convertToFlatList(contentMeta);
		// returns all contentMaps along with comments
		List<Map<String, Object>> allContentMetas = addComments(listOfContentMetas, identifier, commentMessage, actor);

		Map<String, Set<String>> errorList = validationsService.validationsV2(rootOrg, contentMeta);
		if (!errorList.isEmpty()) {
			throw new ConflictErrorException("Message", errorList);
		}
		Set<String> contentIds = getIdsFromHierarchyMap(contentMeta);
		List<UpdateMetaRequest> updateListMap = createUpdateListForStatusChange(allContentMetas, "Processing", actor,
				true);
		response = publishContentV2(rootOrg, updateListMap, transaction, contentIds, identifier, appName, appUrl, org);
		return response;
	}

	@SuppressWarnings({ "unchecked" })
	@Override
	public Response statusChange(String identifier, Map<String, Object> requestBody)
			throws BadRequestException, Exception {
		String rootOrg = null;
		String org = null;
		Integer change = null;
		String commentMessage;
		String actor;
		String appName;
		String appUrl;
		rootOrg = (String) requestBody.get(LexConstants.ROOT_ORG);
		if (rootOrg.isEmpty() || rootOrg == null) {
			throw new BadRequestException("rootOrg is Empty");
		}
		org = (String) requestBody.get(LexConstants.ORG);
		if (org.isEmpty() || org == null) {
			throw new BadRequestException("org is Empty");
		}
		change = (Integer) requestBody.get(LexConstants.OPERATION);
		commentMessage = (String) requestBody.get(LexConstants.COMMENT);
		if (commentMessage == null || commentMessage.isEmpty()) {
			throw new BadRequestException("commentMessage is Empty");
		}
		actor = (String) requestBody.get(LexConstants.ACTOR);
		if (actor.isEmpty() || actor == null) {
			throw new BadRequestException("actor is Empty");
		}
		appName = (String) requestBody.get(LexConstants.APPNAME);
		if (appName == null || appName.isEmpty()) {
			throw new BadRequestException("appName is Empty");
		}
		appUrl = (String) requestBody.get(LexConstants.APPURL);
		if (appUrl == null || appUrl.isEmpty()) {
			throw new BadRequestException("appUrl is Empty");
		}

		// returns complete contentMeta along with conflicting siblings
		Map<String, Object> contentMeta = getContentHierarchy(identifier, rootOrg, org);
		Response response = new Response();
		String currentStatus = contentMeta.get(LexConstants.STATUS).toString();
		String contentType = contentMeta.get(LexConstants.CONTENT_TYPE).toString();
		// delta is used for +1 -1 logic in contentWorkFlow
		Integer delta = null;
		try {
			if (change > 0) {
				delta = 1;
			} else if (change < 0) {
				delta = -1;
			} else if (change == 0) {
				delta = 0;
			}
		} catch (Exception e) {
			throw new BadRequestException("Invalid Input change : " + change);
		}

		// cassandra operation to fetch work-flow for given root_org
		ContentWorkFlowModel casResult = contentWorkFlowRepo.findByPrimaryKeyContentWorkFlow(rootOrg, org, contentType);
		if (casResult == null) {
			throw new BadRequestException("Could not find any data from table for rootOrg: " + rootOrg + ", org: " + org
					+ ", contentType: " + contentType);
		}
		// Stores the contentWorkFlow LifeCycle for given root_org and contentType
		List<String> contentWorkFlow = casResult.getContent_work_flow();
		if (contentWorkFlow == null || contentWorkFlow.isEmpty()) {
			throw new ApplicationLogicError(
					"Table data is corrupt for root_org: " + rootOrg + " org: " + org + " contentType: " + contentType);
		}
		// Stores all functions to be performed during a statusChange operation
		List<String> workFlowOperations = casResult.getWork_flow_operations();
		if (workFlowOperations == null || workFlowOperations.isEmpty()) {
			throw new ApplicationLogicError(
					"Table data is corrupt for root_org: " + rootOrg + " org: " + org + " contentType: " + contentType);
		}

		// index stores integer value of current status
		int index = contentWorkFlow.indexOf(currentStatus);
		if (index == 0 && delta == -1) {
			throw new BadRequestException(
					"Content already at stage: " + currentStatus + ", Cannot go back any further");
		}
		String nextStatus;
		if (delta == 0) {
			// draft
			nextStatus = contentWorkFlow.get(0);
		} else {
			nextStatus = contentWorkFlow.get(index + delta);
		}

		int opVal = contentWorkFlow.indexOf(nextStatus);
		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> operationsMap = new HashMap<>();
		try {
			// stores all operations that need to be performed for given nextStatus
			operationsMap = mapper.readValue(workFlowOperations.get(opVal), HashMap.class);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}

		boolean operationsComplete = false;
		List<String> operationsToBePerformed = (List<String>) operationsMap.get(nextStatus);
		for (String operation : operationsToBePerformed) {
			if (operation.equalsIgnoreCase("validations")) {
				Map<String, Set<String>> errorList = validationsService.validationsV2(rootOrg, contentMeta);
				if (!errorList.isEmpty()) {
					throw new ConflictErrorException("Validation Failed", errorList);
				}
			}
		}
		operationsComplete = true;

		// returns the contents corresponding to the top-level creators
		hierarchyForStatusChange(contentMeta);

		// validates all children to check if status is appropriate
		// validateChildrenStatus(contentMeta, currentStatus);

		// all content-ids corresponding to the given author
		Set<String> contentIds = getIdsFromHierarchyMap(contentMeta);

		// the main identifier
		identifier = (String) contentMeta.get(LexConstants.IDENTIFIER);

		// childTitle and childDesc is calculated here
		calcChildTitleDesc(contentMeta);

		// check if top level identifier is standAlone or not
		boolean isStandAlone = checkStandAlone(identifier, rootOrg);
		contentMeta.put(LexConstants.ISSTANDALONE, isStandAlone);

		// returns all contentMaps in a flat list
		List<Map<String, Object>> listOfContentMetas = convertToFlatList(contentMeta);

		// removes all Live & those contents which are at ahead status meta objects from
		// the flat-list of contentMeta
		List<Map<String, Object>> finalListOfContentMeta = filterLiveContents(listOfContentMetas, currentStatus,
				contentWorkFlow);

		// returns all contentMaps along with comments
		List<Map<String, Object>> allContentMetas = addComments(finalListOfContentMeta, identifier, commentMessage,
				actor);

		Session session = neo4jDriver.session();

		Transaction transaction = session.beginTransaction();
		if (operationsComplete) {
			if (opVal == (contentWorkFlow.size() - 1)) {
				List<UpdateMetaRequest> updateListMap = createUpdateListForStatusChange(allContentMetas, nextStatus,
						actor, true);
				response = publishContentV2(rootOrg, updateListMap, transaction, contentIds, identifier, appName,
						appUrl, org);
//				response = publishContent(contentMeta, rootOrg);
			} else {
				try {
					List<UpdateMetaRequest> updateListMap = createUpdateListForStatusChange(allContentMetas, nextStatus,
							actor, false);
					graphService.updateNodesV2(rootOrg, updateListMap, transaction);
					transaction.commitAsync().toCompletableFuture().get();
					response.put("Message", "Operation Successful, Status has been changed to: " + nextStatus);
				} catch (Exception e) {
					e.printStackTrace();
					transaction.rollbackAsync().toCompletableFuture().get();
					throw e;
				} finally {
					session.close();
				}
			}
		}
		return response;
	}

	private boolean checkStandAlone(String identifier, String rootOrg) {
		String query = "match(n{identifier:'" + identifier + "'})-[r:Has_Sub_Content]->(s) where n:Shared or n:"
				+ rootOrg + " and s:Shared or s:" + rootOrg + " return s";
		Session session = neo4jDriver.session();
		Map<String, Object> parentMap = session.readTransaction(new TransactionWork<Map<String, Object>>() {
			@Override
			public Map<String, Object> execute(Transaction tx) {
				return runQuery(query, tx);
			}
		});
		session.close();
		if (parentMap == null || parentMap.isEmpty()) {
			return true;
		} else {
			return false;
		}
	}

	private List<Map<String, Object>> filterLiveContents(List<Map<String, Object>> listOfContentMetas,
			String currentStatus, List<String> validStatus) throws BadRequestException {
		List<Map<String, Object>> contentMetas = new ArrayList<>();
		int currentIndex = validStatus.indexOf(currentStatus);
		for (Map<String, Object> mapObj : listOfContentMetas) {
			if (!mapObj.get(LexConstants.STATUS).equals(LexConstants.Status.Live.getStatus())) {
				String mapStatus = (String) mapObj.get(LexConstants.STATUS);
				int mapIndex = validStatus.indexOf(mapStatus);
				if (mapIndex < currentIndex) {
					throw new BadRequestException("Cannot change status " + mapObj.get(LexConstants.IDENTIFIER)
							+ " is at a previous status : " + mapStatus);
				} else if (mapIndex == currentIndex) {
					contentMetas.add(mapObj);
				}
			}
		}
		return contentMetas;
	}

	private List<UpdateMetaRequest> createUpdateListForStatusChange(List<Map<String, Object>> allContents,
			String nextStatus, String lastUpdatedBy, boolean populatePublishedBy) {
		List<UpdateMetaRequest> updateList = new ArrayList<>();
		ObjectMapper mapper = new ObjectMapper();
		for (Map<String, Object> contentMeta : allContents) {
			Map<String, Object> updateMap = new HashMap<>();
			if (populatePublishedBy) {
				// TODO
				updateMap.put(LexConstants.ISSTANDALONE, contentMeta.get(LexConstants.ISSTANDALONE));
				updateMap.put(LexConstants.CHILD_TITLE, contentMeta.get(LexConstants.CHILD_TITLE));
				updateMap.put(LexConstants.CHILD_DESC, contentMeta.get(LexConstants.CHILD_DESC));
				updateMap.put(LexConstants.PUBLISHED_BY, lastUpdatedBy);
			}

			updateMap.put(LexConstants.IDENTIFIER, contentMeta.get(LexConstants.IDENTIFIER));
			updateMap.put(LexConstants.ACTOR, lastUpdatedBy);
			updateMap.put(LexConstants.COMMENTS, contentMeta.get(LexConstants.COMMENTS));
			Map<String, String> timeMap = getTimeAndEpochAtPresent();
			Calendar validTill = Calendar.getInstance();
			updateMap.put(LexConstants.LAST_UPDATED, inputFormatterDateTime.format(validTill.getTime()));
			updateMap.put(LexConstants.VERSION_KEY, timeMap.get("versionKey"));
			updateMap.put(LexConstants.STATUS, nextStatus);
			UpdateMetaRequest updateMapReq = new UpdateMetaRequest((String) contentMeta.get(LexConstants.IDENTIFIER),
					updateMap);
			updateList.add(updateMapReq);
		}
		return updateList;
	}

	private Map<String, String> getTimeAndEpochAtPresent() {
		Map<String, String> timeAndEpochMap = new HashMap<String, String>();

		Date presentDate = new Date();
		String format = inputFormatterDateTime.format(presentDate);
		String formattedDate = format;
		String versionKey = String.valueOf(presentDate.getTime());
		timeAndEpochMap.put("formattedDate", formattedDate);
		timeAndEpochMap.put("versionKey", versionKey);
		return timeAndEpochMap;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> addComments(List<Map<String, Object>> allContents, String topLevelId,
			String message, String actor) throws BadRequestException, Exception {
		UUID tempObj = null;
		try {
			tempObj = UUID.fromString(actor);
		} catch (ClassCastException | IllegalArgumentException e) {
			throw new BadRequestException("ACTOR MUST BE A VALID UUID");
		} catch (Exception e) {
			throw new ApplicationLogicError("userId");
		}
		List<Map<String, Object>> updatedContentObjs = new ArrayList<>();
		for (Map<String, Object> content : allContents) {
			List<Map<String, Object>> allPreviousComments = new ArrayList<>();
			if (content.containsKey(LexConstants.COMMENTS)) {
				allPreviousComments = (List<Map<String, Object>>) content.get(LexConstants.COMMENTS);
			}
			int sizeOfComments = allPreviousComments.size();
			if (sizeOfComments > 5) {
				System.out.println("Exceeded comments limit");
				allPreviousComments = sortComments(allPreviousComments);
			}
			Map<String, String> timeMap = getTimeAndEpochAtPresent();
			String value = timeMap.get("formattedDate");
			List<Map<String, Object>> copyOfList = new ArrayList<>(allPreviousComments);
			Map<String, Object> newComment = new HashMap<>();
			if (content.get(LexConstants.IDENTIFIER).equals(topLevelId)) {
				newComment.put(LexConstants.COMMENT, message);
				newComment.put(LexConstants.DATE, value);
				newComment.put(LexConstants.ID, actor);
				copyOfList.add(newComment);
			} else {
				String autoMessage = "Auto moved along with identifier : " + topLevelId + " Message: " + message;
				newComment.put(LexConstants.COMMENT, autoMessage);
				newComment.put(LexConstants.DATE, value);
				newComment.put(LexConstants.ID, actor);
				copyOfList.add(newComment);
			}
			content.put(LexConstants.COMMENTS, copyOfList);
			updatedContentObjs.add(content);
		}
		return updatedContentObjs;

	}

	private List<Map<String, Object>> sortComments(List<Map<String, Object>> masterComments) {
		List<Map<String, Object>> latestFilteredComments = new ArrayList<>();
		DateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmssZ");
		Collections.sort(masterComments, new Comparator<Map<String, Object>>() {
			@Override
			public int compare(Map<String, Object> o1, Map<String, Object> o2) {
				try {
					return df.parse((String) o1.get(LexConstants.DATE))
							.compareTo(df.parse((String) o2.get(LexConstants.DATE)));
				} catch (ParseException e) {
					e.printStackTrace();
				}
				return 0;
			}
		});
		Collections.reverse(masterComments);
		latestFilteredComments = masterComments.subList(0, 5);
		return latestFilteredComments;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Set<String> getIdsFromHierarchyMap(Map<String, Object> contentMeta) {
		Set<String> listOfContentIds = new HashSet<>();
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(contentMeta);
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			listOfContentIds.add(parent.get(LexConstants.IDENTIFIER).toString());
			ArrayList childrenList = (ArrayList) parent.get(LexConstants.CHILDREN);
			parentObjs.addAll(childrenList);
		}
		return listOfContentIds;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Map<String, Object> orderChildren(Map<String, Object> hierarchyMap) {

		hierarchyMap = (Map<String, Object>) hierarchyMap.get(LexConstants.CONTENT);
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(hierarchyMap);

		while (!parentObjs.isEmpty()) {

			Map<String, Object> parent = parentObjs.poll();
			ArrayList childrenList = (ArrayList) parent.get(LexConstants.CHILDREN);

			Collections.sort(childrenList, new Comparator<Map<String, Long>>() {
				@Override
				public int compare(Map<String, Long> o1, Map<String, Long> o2) {
					return o1.get("index").compareTo(o2.get("index"));
				}
			});

			parentObjs.addAll(childrenList);
		}

		return hierarchyMap;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private List<Map<String, Object>> convertToFlatList(Map<String, Object> contentMeta) {
		List<Map<String, Object>> contentList = new ArrayList<>();
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(contentMeta);
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			List<Map<String, Object>> childrenList = (ArrayList) parent.get(LexConstants.CHILDREN);
			contentList.add(parent);
			parentObjs.addAll(childrenList);
		}
		return contentList;
	}

	@SuppressWarnings("unchecked")
	private void hierarchyForStatusChange(Map<String, Object> contentMeta) {
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(contentMeta);

		List<Map<String, Object>> creatorContacts = (List<Map<String, Object>>) contentMeta
				.get(LexConstants.CREATOR_CONTACTS);
		Set<String> masterCreators = getAllCreators(creatorContacts);
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			List<Map<String, Object>> childrenList = (List<Map<String, Object>>) parent.get(LexConstants.CHILDREN);
			List<Map<String, Object>> validChildren = new ArrayList<>();
			for (Map<String, Object> child : childrenList) {
				List<Map<String, Object>> creators = (List<Map<String, Object>>) child
						.get(LexConstants.CREATOR_CONTACTS);
				Set<String> creatorsOfContent = getAllCreators(creators);
				creatorsOfContent.retainAll(masterCreators);
				if (creatorsOfContent.size() > 0) {
					validChildren.add(child);
				}
			}
			parent.put(LexConstants.CHILDREN, validChildren);
			parentObjs.addAll(childrenList);
		}
	}

	private Response publishContentV2(String rootOrg, List<UpdateMetaRequest> updateListMap, Transaction transaction,

			Set<String> contentIds, String topLevelIdentifier, String appName, String appUrl, String org)
			throws Exception {

		Response response = new Response();
		Map<String, Object> kafkaMap = new HashMap<>();
		Session session = neo4jDriver.session();
		kafkaMap.put("topLevelContentId", topLevelIdentifier);
		kafkaMap.put("contentIds", contentIds);
		kafkaMap.put("org", org);
		kafkaMap.put(LexConstants.APPNAME, appName);
		kafkaMap.put(LexConstants.APPURL, appUrl);
		kafkaMap.put(LexConstants.ROOT_ORG, rootOrg);
		ObjectMapper mapper = new ObjectMapper();
		try {
			graphService.updateNodesV2(rootOrg, updateListMap, transaction);
			transaction.commitAsync().toCompletableFuture().get();
			String kafkaMessage = mapper.writeValueAsString(kafkaMap);
			System.out.println("---------------------");
			System.out.println(kafkaMessage);
			kafkaTemplate.send("publishpipeline-stage1", null, kafkaMessage);
			response.put("Message", "Operation Successful: Sent to Publish Pipeline");
			return response;
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			e.printStackTrace();
			throw e;
		} finally {
			session.close();
		}
	}

	@SuppressWarnings("unchecked")
	private void validateChildrenStatus(Map<String, Object> contentMeta, String currentStatus) {
		Map<String, Object> copyMap = new HashMap<>(contentMeta);
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(copyMap);
		List<Map<String, String>> invalidIds = new ArrayList<>();
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			List<Map<String, Object>> childrenList = (List<Map<String, Object>>) parent.get(LexConstants.CHILDREN);
			for (Map<String, Object> child : childrenList) {
				if (!child.get(LexConstants.STATUS).equals(currentStatus)) {
					Map<String, String> testMap = new HashMap<>();
					testMap.put((String) child.get(LexConstants.IDENTIFIER), (String) child.get(LexConstants.STATUS));
					invalidIds.add(testMap);
				}
			}
			parent.put(LexConstants.CHILDREN, childrenList);
			parentObjs.addAll(childrenList);
		}
		if (invalidIds.size() > 0) {
			throw new BadRequestException(
					"Not all Ids are at current common status of " + currentStatus + " Invalid Ids are " + invalidIds);
		}
	}

	private Set<String> getAllCreators(List<Map<String, Object>> creatorContacts) {

		Set<String> allCreators = new HashSet<>();

		for (Map<String, Object> creatorObj : creatorContacts) {
			try {
				allCreators.add(creatorObj.get(LexConstants.ID).toString());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return allCreators;
	}

	private Set<String> getAllCreators(String creatorContacts) {
		ObjectMapper mapper = new ObjectMapper();
		Set<String> allCreators = new HashSet<>();
		List<Map<String, Object>> listObjs = new ArrayList<>();
		try {
			listObjs = mapper.readValue(creatorContacts, ArrayList.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		for (Map<String, Object> creatorObj : listObjs) {
			try {
				allCreators.add(creatorObj.get(LexConstants.ID).toString());
			} catch (Exception e) {
				e.printStackTrace();
			}

		}
		return allCreators;
	}

	@SuppressWarnings("unchecked")
	private void hierachyForViewing(Map<String, Object> hierarchy) throws BadRequestException,Exception {

		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(hierarchy);
		if(hierarchy.get(LexConstants.CREATOR_CONTACTS)==null || hierarchy.get(LexConstants.CREATOR_CONTACTS).toString().isEmpty()) {
			throw new BadRequestException("Corrput Meta CreatorContacts does not exist for : " + hierarchy.get("identifier"));
		}
		String stringCreatorContacts = hierarchy.get(LexConstants.CREATOR_CONTACTS).toString();
		Set<String> masterCreators = getAllCreators(stringCreatorContacts);
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			GraphUtil.mapParser(parent, true);
			List<Map<String, Object>> childrenList = (ArrayList) parent.get(LexConstants.CHILDREN);
			Set<String> identifierSet = new HashSet<>();
			List<Map<String, Object>> validChildren = new ArrayList<>();
			for (Map<String, Object> child : childrenList) {
				validChildren.add(child);
				identifierSet.add(child.get(LexConstants.IDENTIFIER).toString());
			}
			List<Map<String, Object>> validChildrenCopy = new ArrayList<>(validChildren);
			for (Map<String, Object> validChild : validChildrenCopy) {
				String itId = validChild.get(LexConstants.IDENTIFIER).toString();
				String itIdImg = itId + LexConstants.IMG_SUFFIX;

				if (identifierSet.contains(itId) && identifierSet.contains(itIdImg)) {
					Map<String, Object> orgNode = new HashMap<>();
					Map<String, Object> imgNode = new HashMap<>();
					for (Map<String, Object> child : validChildrenCopy) {
						if (child.get(LexConstants.IDENTIFIER).toString().equals(itId)) {
							orgNode = child;
						}
						if (child.get(LexConstants.IDENTIFIER).toString().equals(itIdImg)) {
							imgNode = child;
						}
					}
					String orgCreators = (String) orgNode.get(LexConstants.CREATOR_CONTACTS);
					Set<String> orgCreatorEmails = getAllCreators(orgCreators);
					String imgCreators = (String) imgNode.get(LexConstants.CREATOR_CONTACTS);
					Set<String> imgCreatorEmails = getAllCreators(imgCreators);
					Set<String> orgCopySet = new HashSet<>(orgCreatorEmails);
					Set<String> imgCopySet = new HashSet<>(imgCreatorEmails);
					orgCopySet.retainAll(masterCreators);
					imgCopySet.retainAll(masterCreators);
					if (orgCopySet.size() > 0 && imgCopySet.size() > 0) {
						validChildren.remove(orgNode);
					} else if (orgCopySet.size() > 0 && imgCopySet.size() == 0) {
						validChildren.remove(imgNode);
					} else if (orgCopySet.size() == 0 && imgCopySet.size() > 0) {
						validChildren.remove(orgNode);
					}
				}
			}
			parent.put(LexConstants.CHILDREN, validChildren);
			parentObjs.addAll(childrenList);
		}

	}

	// DO NOT REMOVE WILL BE USED LATER
	@SuppressWarnings("unchecked")
	private void hierarchyForSpecificAuthor(Map<String, Object> contentMeta, String creatorEmail) {
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		// TODO check if creatorEMail is valid UUID
		parentObjs.add(contentMeta);
		ObjectMapper mapper = new ObjectMapper();
		while (!parentObjs.isEmpty()) {
			// pull out top level map
			Map<String, Object> parent = parentObjs.poll();
			// iterate on its children
			List<Map<String, Object>> childrenList = (ArrayList) parent.get(LexConstants.CHILDREN);
			Set<String> iteratorSet = new HashSet<>();
			List<Map<String, Object>> validChildren = new ArrayList<>();
			for (Map<String, Object> child : childrenList) {
				validChildren.add(child);
				// add all 1st level children Ids to a set
				iteratorSet.add(child.get(LexConstants.IDENTIFIER).toString());
			}
			List<Map<String, Object>> validChildrenCopy = new ArrayList<>(validChildren);
			// iterate on all 1st level children
			for (Map<String, Object> validChild : validChildrenCopy) {
				String itId = validChild.get(LexConstants.IDENTIFIER).toString();
				String itIdImg = itId + LexConstants.IMG_SUFFIX;
				// if 1st level children Ids contains both orgNode and imgNode,
				// then one of them
				// must be removed
				if (iteratorSet.contains(itId) && iteratorSet.contains(itIdImg)) {
					Set<String> creatorEmails = new HashSet<>();
					String listOfCreators = (String) validChild.get(LexConstants.CREATOR_CONTACTS);
					List<Map<String, Object>> listObj = new ArrayList<>();
					try {
						// we get list of all creators from map
						listObj = mapper.readValue(listOfCreators, ArrayList.class);
					} catch (Exception e) {
						e.printStackTrace();
					}
					for (Map<String, Object> creatorObj : listObj) {
						// iterate on above obtained list and all emails to a
						// set
						creatorEmails.add(creatorObj.get(LexConstants.ID).toString());
					}
					// if UI provided email is present in set obtained above
					if (creatorEmails.contains(creatorEmail)) {
						// then remove the orgNode
						validChildren.remove(validChild);
					} else {
						// else remove corresponding .img node
						validChildrenCopy.forEach(vcc -> {
							if (vcc.get(LexConstants.IDENTIFIER).toString().equals(itIdImg)) {
								validChildren.remove(vcc);
							}
						});
					}
				}
			}
			parent.put(LexConstants.CHILDREN, validChildren);
			parentObjs.addAll(childrenList);
		}
	}

	private User verifyUser(String userEmail) throws Exception {

		logger.info("Verifying email " + userEmail);
		User user = userRepo.findByEmail(userEmail);

		if (user == null) {
			logger.error("No user found with email : " + userEmail);
			throw new ResourceNotFoundException("No user found with email : " + userEmail);
		}

		logger.info(userEmail + " verified successfully");
		return user;
	}

	// TODO call this somewhere during getHierarchy
	@SuppressWarnings("unchecked")
	private void filterContentOnAccessPaths(Map<String, Object> contentMeta, String author, String rootOrg) {
		User user = null;
		try {
			user = verifyUser(author);
		} catch (Exception e) {
			e.printStackTrace();
		}
		String uuid = user.getId();
		// URL prefix to fetch all User Access Paths
		String accessUrlPrefix = lexServerProps.getAccessUrlPrefix();
		String uri = accessUrlPrefix + "/user/" + uuid + "?rootOrg=" + rootOrg;
		Map<String, Object> result = restTemplate.getForObject(uri, HashMap.class);
		result = (Map<String, Object>) result.get("result");
		// all Aps for a user
		Set<String> accessPathsForUser = new HashSet<>((List<String>) result.get(LexConstants.COMBINED_ACCESS_PATHS));
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(contentMeta);

		while (!parentObjs.isEmpty()) {
			// pull out top-level contentMeta
			Map<String, Object> parent = parentObjs.poll();
			List<Map<String, Object>> childrenList = (ArrayList) parent.get(LexConstants.CHILDREN);
			List<Map<String, Object>> validChildren = new ArrayList<>();
			for (Map<String, Object> child : childrenList) {
				// add all 1st level children Ids to a list<Map>
				validChildren.add(child);
			}

			List<Map<String, Object>> validChildrenCopy = new ArrayList<>(validChildren);
			// iterate on all 1st level children
			try {
				for (Map<String, Object> validChild : validChildrenCopy) {
					Set<String> accessPathsFromContent = new HashSet<>(
							(List<String>) validChild.get(LexConstants.ACCESS_PATHS));
					Set<String> tempSet = new HashSet<>(accessPathsFromContent);
					// get intersection of APs from content and Aps of user
					tempSet.retainAll(accessPathsForUser);
					// if no elements in intersection set
					if (tempSet.size() <= 0) {
						// remove child from list of 1st level children
						System.out.println("Invalid Child : " + validChild.get(LexConstants.IDENTIFIER).toString());
						int i = validChildrenCopy.indexOf(validChild);
						Map<String, Object> tempMap = new HashMap<>();
						tempMap.put(LexConstants.IDENTIFIER, validChild.get(LexConstants.IDENTIFIER));
						tempMap.put(LexConstants.DURATION, validChild.get(LexConstants.DURATION));
						validChildren.set(i, tempMap);
//						validChildren.remove(validChild);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			// put all validChildren in the contentMeta Popped earlier
			parent.put(LexConstants.CHILDREN, validChildren);
			// continue iteration for remaining valid children
			parentObjs.addAll(childrenList);
		}
	}

	@SuppressWarnings("unchecked")
	private void populateMetaForCreation(String rootOrg, String org, Map<String, Object> contentMeta, User user)
			throws Exception {

		logger.info("populating meta for identifier :" + contentMeta.get(LexConstants.IDENTIFIER));

		// status cannot be set while creating content.
		if (contentMeta.containsKey(LexConstants.STATUS)) {
			throw new BadRequestException("status cannot be set while creation");
		}
		String postUrl = lexServerProps.getAccessUrlPostFix();
		postUrl = postUrl.replace("@userId", user.getId().toString());
		postUrl = postUrl.replace("@rootOrg", rootOrg);
		String url = lexServerProps.getAccessUrlPrefix() + postUrl;
		Map<String, Object> userAPMap = restTemplate.getForObject(url, Map.class);
		userAPMap = (Map<String, Object>) userAPMap.get("result");
		List<String> combinedAccessPaths = (List<String>) userAPMap.get(LexConstants.COMBINED_ACCESS_PATHS);

		String apFromReq = (String) contentMeta.get(LexConstants.ACCESS_PATHS);
		List<String> accessPaths = new ArrayList<>();
		if (combinedAccessPaths.contains(apFromReq)) {
			accessPaths.add(apFromReq);
		} else {
			accessPaths.add(rootOrg + "/" + org);
		}

		String contentType = (String) contentMeta.get(LexConstants.CONTENT_TYPE);
		String mimeType = (String) contentMeta.get(LexConstants.MIME_TYPE);
		String createdBy = (String) contentMeta.get(LexConstants.CREATED_BY);
		String fileType = null;
		if (mimeType.contains("mp4") || mimeType.contains("youtube")) {
			fileType = "Video";
		} else if (mimeType.contains("mpeg")) {
			fileType = "Audio";
		} else if (mimeType.contains("web-module")) {
			fileType = "Web Page";
		} else {
			fileType = "Document";
		}
		// bare minimum for content creation
		if (contentType == null || contentType.isEmpty() || mimeType == null || mimeType.isEmpty() || createdBy == null
				|| createdBy.isEmpty()) {
			throw new BadRequestException(
					"Invalid meta for creation. request body must contain contentType, mimeType, createdBy");
		}
		contentMeta.put(LexConstants.ROOT_ORG, rootOrg);
		contentMeta.put(LexConstants.IS_SEARCHABLE, true);
		contentMeta.remove(LexConstants.CREATED_BY);
		contentMeta.put(LexConstants.CREATOR, createdBy);
		contentMeta.put(LexConstants.NODE_TYPE, LexConstants.LEARNING_CONTENT_NODE_TYPE);
		contentMeta.put(LexConstants.STATUS, LexConstants.DRAFT);
		contentMeta.put(LexConstants.DURATION, 0);
		contentMeta.put(LexConstants.ARTIFACT_URL, "");
		contentMeta.put(LexConstants.ACCESS_PATHS, accessPaths);
		contentMeta.put(LexConstants.CHILD_TITLE, new ArrayList<>());
		contentMeta.put(LexConstants.CHILD_DESC, new ArrayList<>());
		contentMeta.put(LexConstants.IS_STAND_ALONE, true);
		contentMeta.put(LexConstants.LEARNING_MODE, "Self-Paced");
		contentMeta.put(LexConstants.FILETYPE, fileType);
		contentMeta.put(LexConstants.SIZE, 0);
		
		Map<String,Object> transcodeMap = new HashMap<>();
		transcodeMap.put(LexConstants.TRANSCODE_STATUS, null);
		transcodeMap.put(LexConstants.TRANSCODED_ON, null);
		transcodeMap.put(LexConstants.RETRYCOUNT, 0);
		
		contentMeta.put(LexConstants.TRANSCODING, transcodeMap);

		// populating org list
		Calendar validTill = Calendar.getInstance();
		contentMeta.put(LexConstants.VERSION_DATE, inputFormatterDateTime.format(validTill.getTime()));
		contentMeta.put(LexConstants.LAST_UPDATED, inputFormatterDateTime.format(validTill.getTime()));
		validTill.add(Calendar.YEAR, 50);

		Map<String, Object> orgMap = new HashMap<>();
		orgMap.put(LexConstants.ORG, org);
		orgMap.put(LexConstants.VALID_TILL, inputFormatterDateTime.format(validTill.getTime()));
		List<Map<String, Object>> orgsList = new ArrayList<>();
		orgsList.add(orgMap);
		contentMeta.put(LexConstants.ORG, orgsList);

		Calendar dueDate = Calendar.getInstance();
		dueDate.add(Calendar.MONTH, 6);
		contentMeta.put(LexConstants.EXPIRY_DATE, inputFormatterDateTime.format(dueDate.getTime()));

		// validationsService.validateMetaFields(rootOrg, contentMeta);

		Map<String, Object> creatorContact = new HashMap<>();
		creatorContact.put(LexConstants.ID, user.getId());
		creatorContact.put(LexConstants.NAME, user.getEmail().substring(0, user.getEmail().indexOf("@")));

		List<Map<String, Object>> creatorContacts = Arrays.asList(creatorContact);
		contentMeta.put(LexConstants.CREATOR_CONTACTS, creatorContacts);

		logger.info("populated meta succesfully for identifier :" + contentMeta.get(LexConstants.IDENTIFIER));
	}

	private String findOriginalLangContent(String translationOf, String rootOrg) {
		String orgIdentifier = translationOf;
		String query = "match(n{identifier:'" + translationOf + "'})-[r:Is_Translation_Of]->(s) where n:Shared or n:"
				+ rootOrg + " and s:Shared or s:" + rootOrg + " return s";
		Session session = neo4jDriver.session();
		Map<String, Object> originalLangNode = session.readTransaction(new TransactionWork<Map<String, Object>>() {
			@Override
			public Map<String, Object> execute(Transaction tx) {
				return runQuery(query, tx);
			}
		});
		if (originalLangNode != null) {
			if (originalLangNode.size() > 0) {
				orgIdentifier = (String) originalLangNode.get(LexConstants.IDENTIFIER);
			}
		}
		session.close();
		return orgIdentifier;
	}

	private Map<String, Object> runQuery(String query, Transaction tx) {
		logger.debug("Running Query : " + query);
		Map<String, Object> resultMap = new HashMap<>();
		StatementResult statementResult = tx.run(query);
		List<Record> records = statementResult.list();
		for (Record rec : records) {
			resultMap = rec.get("s").asMap();
		}
		return resultMap;
	}

	private List<UpdateRelationRequest> copyChildrenRelationsForImageNode(String rootOrg, ContentNode node,
			Transaction transaction) throws Exception {

		List<UpdateRelationRequest> updateRelationRequests = new ArrayList<>();
		if (node.getChildren() != null && !node.getChildren().isEmpty()) {
			String startNodeId = node.getIdentifier() + LexConstants.IMG_SUFFIX;

			for (Relation childRelation : node.getChildren()) {
				UpdateRelationRequest updateRelationRequest = new UpdateRelationRequest(startNodeId,
						childRelation.getEndNodeId(),
						Integer.parseInt(childRelation.getMetadata().get(LexConstants.INDEX).toString()));

				updateRelationRequests.add(updateRelationRequest);
			}
		}
		return updateRelationRequests;
	}

	private List<UpdateRelationRequest> copyParentRelationsForImageNode(String rootOrg, ContentNode node,
			Transaction transaction) throws Exception {

		List<UpdateRelationRequest> updateRelationRequests = new ArrayList<>();
		if (node.getParents() != null && !node.getParents().isEmpty()) {
			String endNodeId = node.getIdentifier() + LexConstants.IMG_SUFFIX;

			for (Relation parentRelation : node.getParents()) {
				UpdateRelationRequest updateRelationRequest = new UpdateRelationRequest(parentRelation.getStartNodeId(),
						endNodeId, Integer.parseInt(parentRelation.getMetadata().get(LexConstants.INDEX).toString()));

				updateRelationRequests.add(updateRelationRequest);
			}
		}

		return updateRelationRequests;
	}

	private void checkContentSharingConstraints(ContentNode parentNode, ContentNode childNode) {

		if (!parentNode.getRootOrg().equals(childNode.getRootOrg())
				&& !childNode.getRootOrg().equals(LexConstants.SHARED_CONTENT)) {
			throw new BadRequestException(
					"Content in shared state cannot have children which are in non shared state.");
		}

		if (parentNode.getMetadata().get(LexConstants.IDENTIFIER)
				.equals(childNode.getMetadata().get(LexConstants.IDENTIFIER))) {
			throw new BadRequestException("A content cannot be the children of itself :"
					+ parentNode.getMetadata().get(LexConstants.IDENTIFIER));
		}
	}

	@SuppressWarnings("unchecked")
	private void populateMetaForPlaylist(String rootOrg, Map<String, Object> contentMeta, User user) throws Exception {

		String contentType = (String) contentMeta.get(LexConstants.CONTENT_TYPE);
		String name = (String) contentMeta.get(LexConstants.NAME);
		String visibility = (String) contentMeta.get(LexConstants.VISIBILITY);
		if (contentType == null || name == null || visibility == null || contentType.isEmpty() || name.isEmpty()
				|| visibility.isEmpty() || rootOrg == null || rootOrg.isEmpty()
				|| contentMeta.get(LexConstants.CREATED_BY) == null
				|| contentMeta.get(LexConstants.CREATED_BY).toString().isEmpty()) {
			throw new BadRequestException("Invalid Meta");
		}
		contentMeta.remove(LexConstants.CREATED_BY);
		List<String> accessPaths = new ArrayList<>();
		List<String> children = (List<String>) contentMeta.get(LexConstants.CHILDREN);
		if (visibility.equalsIgnoreCase("private")) {
			accessPaths.add(user.getId());
			contentMeta.put(LexConstants.ACCESS_PATHS, accessPaths);
		} else if (visibility.equalsIgnoreCase("public")) {
			String uuid = user.getId();
			// URL prefix to fetch all User Access Paths
			String accessUrlPrefix = lexServerProps.getAccessUrlPrefix();
			String uri = accessUrlPrefix + "/user/" + uuid + "?rootOrg=" + rootOrg;
			Map<String, Object> result = restTemplate.getForObject(uri, HashMap.class);
			result = (Map<String, Object>) result.get("result");
			// all Aps for a user
			Set<String> accessPathsForUser = new HashSet<>(
					(List<String>) result.get(LexConstants.COMBINED_ACCESS_PATHS));
			String accessPath = (String) contentMeta.get(LexConstants.ACCESS_PATHS);
			if (accessPath == null || accessPath.isEmpty()) {
				throw new BadRequestException("Public Playlist must have accessPath");
			}
			if (accessPathsForUser.contains(accessPath)) {
				contentMeta.put(LexConstants.ACCESS_PATHS, Arrays.asList(accessPath));
			}
		} else {
			throw new BadRequestException("Visibilty can be either public or private, obtained input : " + visibility);
		}

		if (children.size() < 1) {
			throw new BadRequestException("Must contain minimum of 2 contents");
		}

		if (contentType == null || contentType.isEmpty() || name == null || name.isEmpty()) {
			throw new BadRequestException("Invalid meta for creation");
		}

		List<Map<String, Object>> creatorContacts = new ArrayList<>();
		Map<String, Object> creatorContact = new HashMap<>();
		// creatorContact.put(LexConstants.EMAIL, user.getEmail());
		creatorContact.put(LexConstants.ID, user.getId());
		creatorContact.put(LexConstants.NAME, user.getEmail().substring(0, user.getEmail().indexOf("@")));
		creatorContacts.add(creatorContact);

		try {
			contentMeta.put(LexConstants.CREATOR_CONTACTS, new ObjectMapper().writeValueAsString(creatorContacts));
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}

	}

	// TODO update this to fetch both nodes at same query.
	private ContentNode getNodeFromDb(String rootOrg, String identifier, Transaction transaction) throws Exception {

		String imageNodeIdentifier = "";
		if (identifier.contains(LexConstants.IMG_SUFFIX)) {
			imageNodeIdentifier = identifier;
			identifier = identifier.substring(0, identifier.indexOf(LexConstants.IMG_SUFFIX));
		} else {
			imageNodeIdentifier = identifier + LexConstants.IMG_SUFFIX;
		}

		ContentNode imageNode = graphService.getNodeByUniqueIdV3(rootOrg, imageNodeIdentifier, transaction);

		if (imageNode != null) {
			logger.info("image node found for " + identifier);
			return imageNode;
		}

		logger.info("image node not found for " + identifier);
		// fetch original node when image node not found
		ContentNode contentNode = graphService.getNodeByUniqueIdV3(rootOrg, identifier, transaction);

		if (contentNode == null) {
			logger.error("Content with given identifier does not exist");
			throw new ResourceNotFoundException("Content with given identifier does not exist");
		}

		logger.info("Original node found for identifier " + identifier);

		return contentNode;
	}

	/**
	 * creates image node for the given id and provided metadata.
	 *
	 * @param rootOrg
	 * @param contentNode
	 * @param copyParents
	 * @param copyChildren
	 * @param identifier
	 * @param transaction
	 * @return
	 * @throws Exception
	 */
	private ContentNode createImageNode(String rootOrg, ContentNode contentNode, boolean copyParents,
			boolean copyChildren, String identifier, Transaction transaction) throws Exception {

		logger.info("Attempting to create image node for " + identifier);

		String imageNodeIdentifier = identifier + LexConstants.IMG_SUFFIX;
		contentNode.getMetadata().put(LexConstants.IDENTIFIER, imageNodeIdentifier);
		contentNode.getMetadata().put(LexConstants.STATUS, LexConstants.DRAFT);
		contentNode.getMetadata().put(LexConstants.PUBLISHER_DETAILS, new ArrayList<>());
		contentNode.getMetadata().put(LexConstants.COMMENTS, new ArrayList<>());

		graphService.createNodeV2(rootOrg, contentNode.getMetadata(), transaction);

		logger.info("Image node created for " + identifier);

		List<UpdateRelationRequest> updateRelationRequests = new ArrayList<>();

		if (copyParents) {
			updateRelationRequests.addAll(copyParentRelationsForImageNode(rootOrg, contentNode, transaction));
		}

		if (copyChildren) {
			updateRelationRequests.addAll(copyChildrenRelationsForImageNode(rootOrg, contentNode, transaction));
		}

		graphService.createRelations(rootOrg, updateRelationRequests, transaction);
		logger.info("Relation's copied to image node for " + identifier);

		return graphService.getNodeByUniqueIdV3(rootOrg, imageNodeIdentifier, transaction);
	}

	private boolean validateContentType(Map<String, Object> contentMeta) {

		String contentType = contentMeta.get(LexConstants.CONTENT_TYPE).toString();
		if (contentType.equals(LexConstants.ContentType.Resource.getContentType())
				|| contentType.equals(LexConstants.ContentType.Collection.getContentType())
				|| contentType.equals(LexConstants.ContentType.Course.getContentType())
				|| contentType.equals(LexConstants.ContentType.LearningPath.getContentType())
				|| contentType.equals(LexConstants.ContentType.KnowledgeArtifact.getContentType())
				|| contentType.equals(LexConstants.ContentType.LeadershipReport.getContentType())) {
			return true;
		}
		return false;
	}

	private User verifyUserV2(Map<String, Object> contentMeta) throws Exception {

		if (contentMeta.get(LexConstants.CREATED_BY) == null
				|| contentMeta.get(LexConstants.CREATED_BY).toString().isEmpty()) {
			throw new BadRequestException("content creator is not populated");
		}

		String userId = contentMeta.get(LexConstants.CREATED_BY).toString();

		logger.info("Verifying userId " + userId);

		Optional<User> user = userRepo.findById(userId);

		if (!user.isPresent()) {
			logger.error("No user found with userId : " + userId);
			throw new BadRequestException("No user found with userId : " + userId);
		}

		logger.info(userId + " verified successfully.");
		return user.get();
	}

	private String createNode(String rootOrg, Map<String, Object> contentMeta) throws Exception {

		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();

		logger.debug("Attempting to create content node for identifier : " + contentMeta.get(LexConstants.IDENTIFIER));
		try {

			graphService.createNodeV2(rootOrg, contentMeta, transaction);
			transaction.commitAsync().toCompletableFuture().get();

			logger.info(
					"Content node created successfully for identifier : " + contentMeta.get(LexConstants.IDENTIFIER));
			return contentMeta.get(LexConstants.IDENTIFIER).toString();

		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			logger.info("Content node creation failed for identifier : " + contentMeta.get(LexConstants.IDENTIFIER));
			throw e;
		} finally {
			transaction.close();
			session.close();
		}
	}

	private void createChildRelationsPlaylist(String identifier, List<String> children, String rootOrg)
			throws InterruptedException, ExecutionException {
		List<Map<String, Object>> listForCreatingChildRelations = new ArrayList<>();
		for (int i = 0; i < children.size(); i++) {
			String child = children.get(i);
			int index = i;
			Map<String, Object> map = new HashMap<>();
			map.put(LexConstants.END_NODE_ID, child);
			map.put(LexConstants.INDEX, index);
			listForCreatingChildRelations.add(map);
		}
		Session session = neo4jDriver.session();
		Transaction tx = session.beginTransaction();
		try {
			graphService.createChildRelations(rootOrg, identifier, listForCreatingChildRelations, tx);
			tx.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			tx.rollbackAsync().toCompletableFuture().get();
			throw e;
		} finally {
			tx.close();
			session.close();
		}
	}

	// only valid for content type resource
	private ContentNode updateNode(String rootOrg, String identifier, Map<String, Object> requestMap,
			Transaction transaction) throws Exception {

		updateOperationValidations(requestMap);
		// validationsService.validateMetaFields(rootOrg, requestMap);

		Calendar currentDateTime = Calendar.getInstance();
		requestMap.put(LexConstants.LAST_UPDATED, inputFormatterDateTime.format(currentDateTime.getTime()));

		String imageNodeIdentifier = "";
		if (identifier.contains(LexConstants.IMG_SUFFIX)) {
			imageNodeIdentifier = identifier;
			identifier = identifier.substring(0, identifier.indexOf(LexConstants.IMG_SUFFIX));
		} else {
			imageNodeIdentifier = identifier + LexConstants.IMG_SUFFIX;
		}

		ContentNode imageNode = graphService.getNodeByUniqueIdV3(rootOrg, imageNodeIdentifier, transaction);

		if (imageNode != null) {
			if (!imageNode.getMetadata().get(LexConstants.CONTENT_TYPE)
					.equals(LexConstants.ContentType.Resource.getContentType())) {
				throw new BadRequestException("Update operation not supported for collections");
			}
			imageNode = graphService.updateNodeV2(rootOrg, imageNodeIdentifier, requestMap, transaction);
			return imageNode;
		}

		ContentNode contentNode = graphService.getNodeByUniqueIdV3(rootOrg, identifier, transaction);
		if (contentNode == null) {
			throw new ResourceNotFoundException("Content with given identifier does not exist");
		}

		if (!contentNode.getMetadata().get(LexConstants.CONTENT_TYPE)
				.equals(LexConstants.ContentType.Resource.getContentType())) {
			throw new BadRequestException("Update operation not supported for collections");
		}

		if (contentNode.getMetadata().get(LexConstants.STATUS).equals(LexConstants.Status.Live.getStatus())) {
			// population entries to updated and creating the image node
			for (Map.Entry<String, Object> entry : requestMap.entrySet()) {
				contentNode.getMetadata().put(entry.getKey(), entry.getValue());
			}
			// not copying children as resource should not have children
			contentNode = createImageNode(rootOrg, contentNode, true, false, identifier, transaction);
		} else {
			contentNode = graphService.updateNodeV2(rootOrg, identifier, requestMap, transaction);
		}

		return contentNode;
	}

	private void updateOperationValidations(Map<String, Object> requestMap) {

		if (requestMap.containsKey(LexConstants.STATUS) || requestMap.containsKey(LexConstants.IDENTIFIER)) {
			throw new BadRequestException("Cannot set status, identifier of content in update operation");
		}

//		for (String metaKey : updateMap.keySet()) {
//		if (!reqdFields.contains(metaKey)) {
//			throw new BadRequestException("Invalid meta field " + metaKey);
//		}
//	}
	}

	@SuppressWarnings("unchecked")
	private String getRootNode(String rootOrg, Map<String, Object> nodesModified, Map<String, Object> hierarchy)
			throws Exception {

		String rootNodeIdentifier = "";

		for (Map.Entry<String, Object> nodeModified : nodesModified.entrySet()) {
			Map<String, Object> nodeModifiedMetaMap = (Map<String, Object>) nodeModified.getValue();

			if (!nodeModifiedMetaMap.containsKey("root")) {
				throw new BadRequestException(
						"Invalid request body for update hierarchy operation. Should include root field");
			}
			if ((boolean) nodeModifiedMetaMap.get("root") == true) {
				if (rootNodeIdentifier.equals("")) {
					rootNodeIdentifier = nodeModified.getKey().toString();
				} else if (!nodeModified.getKey().toString().equals(rootNodeIdentifier)) {
					throw new BadRequestException("Invalid contract multiple root nodes found in request body.");
				}
			}
		}

		for (Map.Entry<String, Object> hierarchyEntry : hierarchy.entrySet()) {
			Map<String, Object> hierarchyEntryMap = (Map<String, Object>) hierarchyEntry.getValue();
			if (!hierarchyEntryMap.containsKey("root")) {
				throw new BadRequestException(
						"Invalid request body for update hierarchy operation. Should include root field");
			}
			if ((boolean) hierarchyEntryMap.get("root") == true) {
				if (rootNodeIdentifier.equals("")) {
					rootNodeIdentifier = hierarchyEntry.getKey().toString();
				} else if (!hierarchyEntry.getKey().toString().equals(rootNodeIdentifier)) {
					throw new BadRequestException("Invalid contract multiple root node found in request body.");
				}
			}
		}

		if (rootNodeIdentifier.equals("")) {
			throw new BadRequestException("Invalid request body for update Hierachy operation root node not present.");
		}
		return rootNodeIdentifier;
	}

	private void createContentFolderInS3(String rootOrg, String org, String identifier,
			Map<String, Object> contentMeta) {

		rootOrg = rootOrg.replace(" ", "_");
		org = org.replace(" ", "_");

		String url = lexServerProps.getContentServiceUrl() + "/contentv3/directory/" + rootOrg + "%2F" + org + "%2F"
				+ "Public%2F" + identifier;

		if (contentMeta.get(LexConstants.CONTENT_TYPE).equals(LexConstants.ContentType.Resource.getContentType())) {
			url += "%2Fweb-hosted";
		}

		UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
		UriComponents components = builder.build(true);
		URI uri = components.toUri();

		try {
			logger.info("Creating folder in s3 using url " + url);
			restTemplate.postForEntity(uri, null, String.class);
			logger.info("Folder created successfully in s3 for identifier : " + identifier);
		} catch (HttpStatusCodeException httpStatusCodeException) {
			logger.error("Folder creation in s3 failed for identifier : " + identifier + ". Content Service returned "
					+ httpStatusCodeException.getStatusCode() + "  "
					+ httpStatusCodeException.getResponseBodyAsString());
			throw httpStatusCodeException;
		}
	}

	private void checkLearningPathConstraints(ContentNode contentNodeToUpdate, ContentNode childNode) {
		if (contentNodeToUpdate.getMetadata().get(LexConstants.CONTENT_TYPE)
				.equals(LexConstants.ContentType.LearningPath.getContentType())
				&& !childNode.getMetadata().get(LexConstants.STATUS).equals(LexConstants.Status.Live.getStatus())) {
			throw new BadRequestException(childNode.getIdentifier() + " is not in live state");
		}
	}

	private void ensureHierarchyCorrectnessForHierarchyUpdate(Map<String, ContentNode> idToContentMapping,
			ContentNode contentNode) {

		if (contentNode.getChildren() == null || contentNode.getChildren().isEmpty()) {
			return;
		}

		// try removing by overriding equals method of relation class
		List<Relation> childRelations = contentNode.getChildren();
		for (Relation childRelation : childRelations) {
			if (idToContentMapping.containsKey(childRelation.getEndNodeId())) {
				ContentNode childNode = idToContentMapping.get(childRelation.getEndNodeId());
				Iterator<Relation> parentRelationsIterator = childNode.getParents().iterator();
				while (parentRelationsIterator.hasNext()) {
					Relation parentRelation = parentRelationsIterator.next();
					if (parentRelation.getStartNodeId().equals(contentNode.getIdentifier())) {
						parentRelationsIterator.remove();
					}
				}
			}
		}
	}

	private void ensureHierarchyCorrectnessForMetaUpdate(Map<String, ContentNode> idToContentMapping,
			ContentNode imageNode) {

		// the relation getting added here should not exist before.
		if (imageNode.getChildren() != null && !imageNode.getChildren().isEmpty()) {
			List<Relation> childrenRelations = imageNode.getChildren();
			for (Relation relation : childrenRelations) {
				if (idToContentMapping.containsKey(relation.getEndNodeId())) {
					ContentNode childNode = idToContentMapping.get(relation.getEndNodeId());
					Relation newRelation = new Relation(imageNode.getIdentifier(), relation.getEndNodeId(),
							relation.getRelationType(), relation.getMetadata());

					childNode.getParents().add(newRelation);
				}
			}
		}

		if (imageNode.getParents() != null && !imageNode.getParents().isEmpty()) {
			List<Relation> parentRelations = imageNode.getParents();
			for (Relation relation : parentRelations) {
				if (idToContentMapping.containsKey(relation.getStartNodeId())) {
					ContentNode parentNode = idToContentMapping.get(relation.getStartNodeId());
					Relation newRelation = new Relation(relation.getStartNodeId(), imageNode.getIdentifier(),
							relation.getRelationType(), relation.getMetadata());

					parentNode.getChildren().add(newRelation);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void getChildrenGettingAttached(List<String> identifiers, Map<String, Object> hierarchy) {
		List<String> childrenGettingAttached = new ArrayList<>();

		// all the nodes which are getting attached are collected in this list
		for (Map.Entry<String, Object> entrySet : hierarchy.entrySet()) {
			Map<String, Object> newHierachyMap = (Map<String, Object>) entrySet.getValue();
			childrenGettingAttached.addAll((List<String>) newHierachyMap.get(LexConstants.CHILDREN));
		}

		childrenGettingAttached.addAll(childrenGettingAttached.stream()
				.map(childGettingAttached -> childGettingAttached + LexConstants.IMG_SUFFIX)
				.collect(Collectors.toList()));

		identifiers.addAll(childrenGettingAttached);
	}

	private ContentNode populateMetaForImageNodeCreation(ContentNode contentNode, Map<String, Object> updateMap) {

		ContentNode imageNode = new ContentNode();

		String imageNodeIdentifier = contentNode.getIdentifier() + LexConstants.IMG_SUFFIX;
		imageNode.setIdentifier(imageNodeIdentifier);
		imageNode.setRootOrg(contentNode.getRootOrg());

		imageNode.setMetadata(new HashMap<>(contentNode.getMetadata()));
		imageNode.getMetadata().put(LexConstants.IDENTIFIER, imageNodeIdentifier);
		imageNode.getMetadata().put(LexConstants.STATUS, LexConstants.DRAFT);
		imageNode.getMetadata().put(LexConstants.PUBLISHER_DETAILS, new ArrayList<>());
		imageNode.getMetadata().put(LexConstants.COMMENTS, new ArrayList<>());

		imageNode.setChildren(new ArrayList<>(contentNode.getChildren()));
		imageNode.setParents(new ArrayList<>(contentNode.getParents()));

		if (updateMap != null && !updateMap.isEmpty())
			updateMap.entrySet()
					.forEach(updateEntry -> imageNode.getMetadata().put(updateEntry.getKey(), updateEntry.getValue()));

		return imageNode;
	}

	private void reCalculateDuration(String rootOrg, String org, String rootNodeIdentifier, Transaction transaction)
			throws Exception {

		List<String> fields = new ArrayList<>();
		Map<String, Object> hierarchyMap = getHierarchyFromNeo4j(rootNodeIdentifier, rootOrg, transaction, false,
				fields);
		hierachyForViewing(hierarchyMap);
		calcDurationUtil(hierarchyMap);
		calcSizeUtil(hierarchyMap);
		updateNewSizeAndDuration(rootOrg, hierarchyMap, transaction);
	}

	@SuppressWarnings("unchecked")
	private void updateNewSizeAndDuration(String rootOrg, Map<String, Object> contentHierarchy, Transaction transaction)
			throws Exception {

		List<UpdateMetaRequest> updateMetaRequests = new ArrayList<>();

		Queue<Map<String, Object>> queue = new LinkedList<>();
		queue.add(contentHierarchy);

		while (!queue.isEmpty()) {
			Map<String, Object> contentMeta = queue.poll();

			Map<String, Object> contentMetaToUpdate = new HashMap<>();
			contentMetaToUpdate.put(LexConstants.DURATION, contentMeta.get(LexConstants.DURATION));
			contentMetaToUpdate.put(LexConstants.SIZE, contentMeta.get(LexConstants.SIZE));

			UpdateMetaRequest updateMetaRequest = new UpdateMetaRequest(
					contentMeta.get(LexConstants.IDENTIFIER).toString(), contentMetaToUpdate);
			updateMetaRequests.add(updateMetaRequest);

			List<Map<String, Object>> children = (List<Map<String, Object>>) contentMeta.get(LexConstants.CHILDREN);
			if (children != null && !children.isEmpty()) {
				queue.addAll(children);
			}
		}
		graphService.updateNodesV2(rootOrg, updateMetaRequests, transaction);
	}

	@SuppressWarnings("unchecked")
	private void calcDurationUtil(Map<String, Object> contentHierarchy) {

		Stack<Map<String, Object>> stack = new Stack<>();
		stack.push(contentHierarchy);

		Map<String, Integer> durationMap = new HashMap<>();
		while (!stack.isEmpty()) {

			Map<String, Object> parent = stack.peek();
			List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get(LexConstants.CHILDREN);

			if (children != null && !children.isEmpty()) {
				boolean durationExistsForChildren = true;
				for (Map<String, Object> child : children) {
					if (!durationMap.containsKey(child.get(LexConstants.IDENTIFIER).toString())) {
						durationExistsForChildren = false;
						stack.push(child);
					}
				}
				if (durationExistsForChildren) {
					stack.pop();
					int parentDuration = 0;
					for (Map<String, Object> child : children) {
						parentDuration += durationMap.get(child.get(LexConstants.IDENTIFIER).toString());
					}
					// if the content node is live then do not update the duration
					if (!parent.get(LexConstants.STATUS).toString().equals(LexConstants.LIVE)) {
						parent.put(LexConstants.DURATION, parentDuration);
					}
					durationMap.put(parent.get(LexConstants.IDENTIFIER).toString(), parentDuration);
				}
			} else {
				stack.pop();
				durationMap.put(parent.get(LexConstants.IDENTIFIER).toString(),
						Integer.parseInt(parent.get(LexConstants.DURATION).toString()));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void calcSizeUtil(Map<String, Object> contentHierarchy) {

		Stack<Map<String, Object>> stack = new Stack<>();
		stack.push(contentHierarchy);

		Map<String, Double> sizeMap = new HashMap<>();
		while (!stack.isEmpty()) {

			Map<String, Object> parent = stack.peek();
			List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get(LexConstants.CHILDREN);

			if (children != null && !children.isEmpty()) {
				boolean sizeExistsForChildren = true;
				for (Map<String, Object> child : children) {
					if (!sizeMap.containsKey(child.get(LexConstants.IDENTIFIER).toString())) {
						sizeExistsForChildren = false;
						stack.push(child);
					}
				}
				if (sizeExistsForChildren) {
					stack.pop();
					Double parentSize = 0d;
					for (Map<String, Object> child : children) {
						parentSize += sizeMap.get(child.get(LexConstants.IDENTIFIER).toString());
					}
					// if the content node is live then do not update the duration
					if (!parent.get(LexConstants.STATUS).toString().equals(LexConstants.LIVE)) {
						parent.put(LexConstants.SIZE, parentSize);
					}
					sizeMap.put(parent.get(LexConstants.IDENTIFIER).toString(), parentSize);
				}
			} else {
				stack.pop();
				sizeMap.put(parent.get(LexConstants.IDENTIFIER).toString(),
						Double.parseDouble(parent.get(LexConstants.SIZE).toString()));
			}
		}
	}

	private Map<String, Object> removeImageSuffixFromNodesModified(Map<String, Object> nodesModified) {

		Map<String, Object> nodesModifiedNew = new HashMap<>();
		for (Map.Entry<String, Object> entry : nodesModified.entrySet()) {
			if (entry.getKey().contains(LexConstants.IMG_SUFFIX)) {
				String key = entry.getKey().substring(0, entry.getKey().indexOf(LexConstants.IMG_SUFFIX));
				nodesModifiedNew.put(key, entry.getValue());
			} else {
				nodesModifiedNew.put(entry.getKey(), entry.getValue());
			}
		}

		return nodesModifiedNew;
	}

	private List<String> removeImageNodeSuffix(List<String> identifiers) {

		Iterator<String> iterator = identifiers.iterator();
		List<String> orignalIds = new ArrayList<>();

		while (iterator.hasNext()) {
			String parentId = iterator.next();
			if (parentId.contains(LexConstants.IMG_SUFFIX)) {
				parentId = parentId.substring(0, parentId.indexOf(LexConstants.IMG_SUFFIX));
				orignalIds.add(parentId);
				iterator.remove();
			}
		}
		identifiers.addAll(orignalIds);

		return identifiers;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> removeImageNodeSuffixFromHierarchyModified(Map<String, Object> hierarchy) {

		Map<String, Object> hierarchyNew = new HashMap<>();
		for (Map.Entry<String, Object> entry : hierarchy.entrySet()) {

			Map<String, Object> newHierarchyContentMap = new HashMap<>((Map<String, Object>) entry.getValue());
			List<String> children = (List<String>) newHierarchyContentMap.get(LexConstants.CHILDREN);
			children = removeImageNodeSuffix(children);

			newHierarchyContentMap.put(LexConstants.CHILDREN, children);

			if (entry.getKey().contains(LexConstants.IMG_SUFFIX)) {
				String key = entry.getKey().substring(0, entry.getKey().indexOf(LexConstants.IMG_SUFFIX));
				hierarchyNew.put(key, newHierarchyContentMap);
			} else {
				hierarchyNew.put(entry.getKey(), newHierarchyContentMap);
			}
		}
		return hierarchyNew;
	}

	@Override
	public Map<String, Object> getContentHierarchyFields(String identifier, String rootOrg, String org,
			Map<String, Object> reqMap) throws Exception {
		Session session = neo4jDriver.session();

		Map<String, Object> hierarchyMap = new HashMap<>();
		List<String> fields = new ArrayList<>();
		boolean fieldsPassed = false;
		try {
			fields = (List<String>) reqMap.get(LexConstants.FIELDS);
			System.out.println(fields);
			fieldsPassed = (boolean) reqMap.get(LexConstants.FIELDS_PASSED);
		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e.getMessage());
		}

		final boolean effFinalValue = fieldsPassed;
		final List<String> effFinalFields = new ArrayList<>(fields);

		hierarchyMap = session.readTransaction(new TransactionWork<Map<String, Object>>() {
			@Override
			public Map<String, Object> execute(Transaction tx) {
				return getHierarchyFromNeo4j(identifier, rootOrg, tx, effFinalValue, effFinalFields);
			}
		});

		String creatorContactsJson = hierarchyMap.get(LexConstants.CREATOR_CONTACTS).toString();
		List<Map<String, Object>> creatorContacts = new ObjectMapper().readValue(creatorContactsJson, List.class);
		hierachyForViewing(hierarchyMap);
		session.close();
		return hierarchyMap;

	}

	public static Map<String, Object> fieldsRequired(List<String> fields, Map<String, Object> nodeMap) {
		Map<String, Object> resultMap = new HashMap<>();
		String identifier = (String) nodeMap.get(LexConstants.IDENTIFIER);
		String creatorContacts = (String) nodeMap.get(LexConstants.CREATOR_CONTACTS);
		resultMap.put(LexConstants.IDENTIFIER, identifier);
		resultMap.put(LexConstants.CREATOR_CONTACTS, creatorContacts);
		for (String field : fields) {
			if (nodeMap.containsKey(field)) {
				resultMap.put(field, nodeMap.getOrDefault(field, " "));
			}
		}
		return resultMap;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Response extendContentExpiry(Map<String, Object> requestBody) throws Exception {
		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();
		Response response = new Response();
		List<String> exclusions = new ArrayList();
		String topLevelIdentifier = null;
		String rootOrg = null;
		String org = null;
		boolean isExtend = false;
		List<UpdateMetaRequest> updateList = new ArrayList<>();
		try {
			topLevelIdentifier = (String) requestBody.get(LexConstants.IDENTIFIER);
			rootOrg = (String) requestBody.get(LexConstants.ROOT_ORG);
			org = (String) requestBody.get(LexConstants.ORG);
			exclusions = (List<String>) requestBody.get(LexConstants.EXCLUSIONS);
			isExtend = (boolean) requestBody.get(LexConstants.ISEXTEND);
			if (topLevelIdentifier == null || topLevelIdentifier.isEmpty() || rootOrg == null || rootOrg.isEmpty()
					|| org == null || org.isEmpty() || exclusions == null) {
				throw new BadRequestException("Invalid request body");
			}

		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e);
		}
		if (isExtend) {
			List<String> tbdContents = getToBeDeletedContents(topLevelIdentifier, rootOrg);
			List<Map<String, Object>> flatList = filterExclusionContents(tbdContents, exclusions);
			updateList = createUpdateListForExpiryChange(flatList);
			System.out.println(updateList);
			try {
				graphService.updateNodesV2(rootOrg, updateList, transaction);
				transaction.commitAsync().toCompletableFuture().get();
				response.put("Message", "Extended content Expiry Date to 6 months");
			} catch (Exception e) {
				e.printStackTrace();
				transaction.rollbackAsync().toCompletableFuture().get();
				throw e;
			} finally {
				session.close();
			}
		} else {
			List<String> tbdContents = getToBeDeletedContents(topLevelIdentifier, rootOrg);
			List<Map<String, Object>> flatList = filterExclusionContents(tbdContents, exclusions);
			updateList = createListForExpiredContent(flatList);
			System.out.println(updateList);
			try {
				graphService.updateNodesV2(rootOrg, updateList, transaction);
				transaction.commitAsync().toCompletableFuture().get();
				response.put("Message", "Operation performed Contents markedForDeletion");
			} catch (Exception e) {
				e.printStackTrace();
				transaction.rollbackAsync().toCompletableFuture().get();
				throw e;
			} finally {
				session.close();
			}
		}
		return response;
	}

	private List<UpdateMetaRequest> createListForExpiredContent(List<Map<String, Object>> flatList) {
		List<UpdateMetaRequest> updateList = new ArrayList<>();
		for (Map<String, Object> contentMeta : flatList) {
			Map<String, Object> updateMap = new HashMap<>();
			updateMap.put(LexConstants.IDENTIFIER, contentMeta.get(LexConstants.IDENTIFIER));
			Map<String, String> timeMap = getTimeAndEpochAtPresent();
			Calendar validTill = Calendar.getInstance();
			updateMap.put(LexConstants.LAST_UPDATED, inputFormatterDateTime.format(validTill.getTime()) + "+0000");
			updateMap.put(LexConstants.VERSION_KEY, timeMap.get("versionKey"));
			updateMap.put(LexConstants.STATUS, LexConstants.MARKED_FOR_DELETION);

			updateMap.put(LexConstants.EXPIRY_DATE, inputFormatterDateTime.format(validTill.getTime()));
			UpdateMetaRequest updateMapReq = new UpdateMetaRequest((String) contentMeta.get(LexConstants.IDENTIFIER),
					updateMap);

			updateList.add(updateMapReq);
		}
		return updateList;
	}

	private List<Map<String, Object>> filterExclusionContents(List<String> ids, List<String> exclusions) {
		List<Map<String, Object>> flatListOfMaps = new ArrayList<>();
		for (String identifier : ids) {
			if (!exclusions.contains(identifier)) {
				Map<String, Object> tempMap = new HashMap<>();
				Calendar dueDate = Calendar.getInstance();
				dueDate.add(Calendar.MONTH, 6);
				tempMap.put(LexConstants.IDENTIFIER, identifier);
				System.out.println(dueDate);
				tempMap.put(LexConstants.EXPIRY_DATE, inputFormatterDateTime.format(dueDate.getTime()) + "+0000");
				flatListOfMaps.add(tempMap);
			}
		}
		return flatListOfMaps;
	}

	private List<UpdateMetaRequest> createUpdateListForExpiryChange(List<Map<String, Object>> allContents) {
		List<UpdateMetaRequest> updateList = new ArrayList<>();
		for (Map<String, Object> contentMeta : allContents) {
			Map<String, Object> updateMap = new HashMap<>();
			updateMap.put(LexConstants.IDENTIFIER, contentMeta.get(LexConstants.IDENTIFIER));
			Map<String, String> timeMap = getTimeAndEpochAtPresent();
			Calendar validTill = Calendar.getInstance();
			updateMap.put(LexConstants.LAST_UPDATED, inputFormatterDateTime.format(validTill.getTime()) + "+0000");
			updateMap.put(LexConstants.VERSION_KEY, timeMap.get("versionKey"));
			updateMap.put(LexConstants.STATUS, "Live");
			updateMap.put(LexConstants.EXPIRY_DATE, contentMeta.get(LexConstants.EXPIRY_DATE));
			UpdateMetaRequest updateMapReq = new UpdateMetaRequest((String) contentMeta.get(LexConstants.IDENTIFIER),
					updateMap);
			updateList.add(updateMapReq);
		}
		return updateList;
	}

	private List<String> getToBeDeletedContents(String identifier, String rootOrg) {
		Session session = neo4jDriver.session();
		List<String> tbdContent = session.readTransaction(new TransactionWork<List<String>>() {

			@Override
			public List<String> execute(Transaction tx) {
				String query = "match(n{identifier:'" + identifier + "'}) where n:Shared or n:" + rootOrg
						+ " with n optional match(n)-[r:Has_Sub_Content*]->(s) where s:Shared or s:" + rootOrg
						+ " and n.status='Live' and s.status='Live' return s.identifier";
				System.out.println("Runnning query");
				StatementResult statementResult = tx.run(query);
				List<Record> records = statementResult.list();
				List<String> tbdContents = new ArrayList<>();
				for (Record rec : records) {
					String id = rec.get("s.identifier").toString();
					id = id.replace("\"", "");
					tbdContents.add(id);
				}
				return tbdContents;
			}
		});
		tbdContent.add(identifier);
		return tbdContent;

	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> getHierarchyForAuthor(Map<String, Object> mapObj, String creatorEmail)
			throws IOException {

		// adding hierarchy map to queue
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(mapObj);
		ObjectMapper mapper = new ObjectMapper();
		UUID tempObj = null;
		try {
			tempObj = UUID.fromString(creatorEmail);
		} catch (ClassCastException | IllegalArgumentException e) {
			throw new BadRequestException("MUST BE A VALID UUID");
		} catch (Exception e) {
			throw new ApplicationLogicError("userId");
		}

		while (!parentObjs.isEmpty()) {

			// pull out top-level parent
			Map<String, Object> parent = parentObjs.poll();
			// added children of parent to list
			List<Map<String, Object>> childrenList = (ArrayList) parent.get(LexConstants.CHILDREN);
			// set to log all visited child-ids for a given parent
			Set<String> iteratorSet = new HashSet<>();
			List<Map<String, Object>> validChildren = new ArrayList<>();

			for (Map<String, Object> child : childrenList) {

				Set<String> creatorEmails = new HashSet<>();
				List<Map<String, Object>> creators = new ArrayList<>();
				try {
					creators = mapper.readValue(child.get(LexConstants.CREATOR_CONTACTS).toString(), ArrayList.class);
				} catch (IOException e) {
					e.printStackTrace();
					throw e;
				}
				for (Map<String, Object> creator : creators) {
					// obtain all creatorEmails from creatorContacts
					creatorEmails.add(creator.get(LexConstants.ID).toString());
				}
				// if provided email is in above created SET then child is valid
				// for author
				if (creatorEmails.contains(creatorEmail)) {
					// add child-id to set
					iteratorSet.add(child.get(LexConstants.IDENTIFIER).toString());
					// add child object to a list of validChildren
					validChildren.add(child);
				}
			}
			List<Map<String, Object>> validChildrenTest = new ArrayList<>(validChildren);
			// iterate on List of Valid Children
			for (Map<String, Object> validChild : validChildrenTest) {
				String itId = validChild.get(LexConstants.IDENTIFIER).toString();
				String itIdImg = itId + LexConstants.IMG_SUFFIX;
				// remove org node if image node is present
				if (iteratorSet.contains(itId) && iteratorSet.contains(itIdImg)) {
					validChildren.remove(validChild);
				}
			}
			// finally add all valid children to parent
			parent.put(LexConstants.CHILDREN, validChildren);
			parentObjs.addAll(childrenList);
		}
		return mapObj;
	}

	private static String getUniqueIdFromTimestamp(int environmentId) {

		Random random = new Random();
		long env = (environmentId + random.nextInt(99999)) / 10000000;
		long uid = System.currentTimeMillis() + random.nextInt(999999);
		uid = uid << 13;
		return env + "" + uid + "" + atomicInteger.getAndIncrement();
	}

	private void createOperationValidations(Map<String, Object> contentMeta) {

		if (contentMeta.containsKey(LexConstants.IDENTIFIER)) {
			throw new BadRequestException("identifier cannot not be present while creating content");
		}

		if (contentMeta.containsKey(LexConstants.CHILDREN)) {
			throw new BadRequestException("children cannot be present while creating a content");
		}

		if (contentMeta.containsKey(LexConstants.COLLECTION)) {
			throw new BadRequestException("collections cannot be present while creating a content");
		}
	}

	@SuppressWarnings("unchecked")
	private void calcChildTitleDesc(Map<String, Object> contentHierarchy) {

		Stack<Map<String, Object>> stack = new Stack<>();
		stack.push(contentHierarchy);

		Map<String, Object> childTitle = new HashMap<>();
		Map<String, Object> childDesc = new HashMap<>();
		while (!stack.isEmpty()) {

			Map<String, Object> parent = stack.peek();
			List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get(LexConstants.CHILDREN);

			if (children == null || children.isEmpty()) {
				stack.pop();
				childTitle.put(parent.get(LexConstants.IDENTIFIER).toString(), parent.get(LexConstants.NAME));
				childDesc.put(parent.get(LexConstants.IDENTIFIER).toString(), parent.get(LexConstants.DESC));
			} else {
				boolean childTitleExists = true;
				boolean childDescExists = true;
				for (Map<String, Object> child : children) {
					if (!childTitle.containsKey(child.get(LexConstants.IDENTIFIER).toString())
							&& !childDesc.containsKey(child.get(LexConstants.IDENTIFIER).toString())) {
						childTitleExists = false;
						childDescExists = false;
						stack.push(child);
					}
				}
				if (childTitleExists && childDescExists) {
					stack.pop();
					List<String> parentTitle = new ArrayList<>();
					List<String> parentDesc = new ArrayList<>();
					for (Map<String, Object> child : children) {
						parentTitle.add((String) childTitle.get(child.get(LexConstants.IDENTIFIER).toString()));
						parentTitle.addAll((List) child.get(LexConstants.CHILD_TITLE));
						parentDesc.add((String) childDesc.get(child.get(LexConstants.IDENTIFIER).toString()));
						parentDesc.addAll((List) child.get(LexConstants.CHILD_DESC));
					}
					// TODO checkSimilar()
					List<String> finalTitleList = checkSimilar((String) parent.get(LexConstants.NAME), parentTitle);
					List<String> finalDescList = checkSimilar((String) parent.get(LexConstants.DESC), parentDesc);
					parent.put(LexConstants.CHILD_TITLE, finalTitleList);
					parent.put(LexConstants.CHILD_DESC, finalDescList);
					childDesc.put(parent.get(LexConstants.IDENTIFIER).toString(), parent.get(LexConstants.NAME));
					childTitle.put(parent.get(LexConstants.IDENTIFIER).toString(), parent.get(LexConstants.DESC));
				}
			}
		}
	}

	private List<String> checkSimilar(String mainString, List<String> listVals) {
		List<String> returnList = new ArrayList<>();
		for (String strVal : listVals) {
			if (strVal.length() > mainString.length()) {
				double val = (double) RatcliffObershelpMetric.compare(strVal, mainString).get();
				if (val <= 0.3) {
					returnList.add(strVal);
				}
			} else {
				double val = (double) RatcliffObershelpMetric.compare(mainString, strVal).get();
				if (val <= 0.3) {
					returnList.add(strVal);
				}
			}
		}
		return returnList;
	}

}
