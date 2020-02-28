/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.serviceimpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.elasticsearch.ResourceNotFoundException;
import org.neo4j.driver.internal.value.ListValue;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.TransactionWork;
import org.neo4j.driver.v1.types.Node;
import org.neo4j.driver.v1.types.Relationship;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.exception.BadRequestException;
import com.infosys.lexauthoringservices.service.GraphService;
import com.infosys.lexauthoringservices.service.ValidationsService;
import com.infosys.lexauthoringservices.util.LexConstants;
import com.infosys.lexauthoringservices.validation.ValidatorV2;
import com.infosys.lexauthoringservices.validation.model.Path;
import com.infosys.lexauthoringservices.validation.model.Paths;

@Service
public class ValidationsServiceImpl implements ValidationsService {

	private ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.USE_LONG_FOR_INTS, true);

	@Autowired
	GraphService graphService;

	@Autowired
	Driver neo4jDriver; // = GraphDatabase.driver("bolt://URL_DEFAULT:7687");

	@Override
	public Map<String, Object> getValidationNode(String identifier) throws Exception {

		Session session = neo4jDriver.session();
		Statement statement = new Statement("match (node:Validation{identifier:'" + identifier + "'}) return node");

		StatementResult result = session.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty()) {
			throw new ResourceNotFoundException("Validation node with " + identifier + " does not exist");
		}
		Record record = records.get(0);

		Map<String, Object> validationNode = record.get("node").asMap();

		validationNode = convertToHashMap(validationNode);
		validationNode.put("validateHere", objectMapper.readValue(validationNode.get("validateHere").toString(),
				new TypeReference<List<Map<String, Object>>>() {
				}));

		return validationNode;
	}

	@Override
	public void putValidationNode(String identifier, Map<String, Object> validationNode) throws Exception {

		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();

		try {
			validationNode.put("validateHere", objectMapper.writeValueAsString(validationNode.get("validateHere")));
			Map<String, Object> paramMap = new HashMap<>();
			paramMap.put("data", validationNode);

			Statement fetchStatement = new Statement(
					"match (node:Validation{identifier:'" + identifier + "'}) return node", paramMap);

			StatementResult result = transaction.run(fetchStatement);
			List<Record> records = result.list();

			if (records == null || records.isEmpty()) {
				Statement createStatement = new Statement("create (node:Validation $data)", paramMap);
				transaction.run(createStatement);
				transaction.commitAsync().toCompletableFuture().get();
			} else {
				Statement putStatement = new Statement(
						"match(node:Validation{identifier:'" + identifier + "'}) set node=$data", paramMap);
				transaction.run(putStatement);
				transaction.commitAsync().toCompletableFuture().get();
			}
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
			throw e;
		} finally {
			transaction.close();
			session.close();
		}
	}

//	public static void main(String args[]) throws Exception {
//
//		Session session = neo4jDriver.session();
//
//		Transaction transaction = session.beginTransaction();
//
//		ContentNode contentNode = new GraphServiceImpl().getNodeByUniqueIdV3("Infosys", "lex_49750091094696960",
//				transaction);
//
//		contentNode.getMetadata().put("children", new ArrayList<>());
//		contentNode.getMetadata().put("resourceType", "Certification");
//		contentNode.getMetadata().put("passPercentage", 10L);
//		System.out.println(new ValidationsServiceImpl().validationsV2("Infosys", contentNode.getMetadata()));
//	}

	@Override
	public Map<String, Object> getValidationRelation(String startNodeId, String endNodeId) {

		Session session = neo4jDriver.session();

		Map<String, Object> relationMap = session.readTransaction(new TransactionWork<Map<String, Object>>() {

			@Override
			public Map<String, Object> execute(Transaction tx) {

				Statement statement = new Statement("match (node:Validation{identifier:'" + startNodeId
						+ "'})-[r]->(node1:Validation{identifier:'" + endNodeId + "'}) return r");

				StatementResult result = tx.run(statement);
				return result.list().get(0).get("r").asRelationship().asMap();
			}
		});

		session.close();
		return relationMap;
	}

	@Override
	public void putValidationRelation(String startNodeId, String endNodeId, Map<String, Object> relationMap)
			throws Exception {

		Session session = neo4jDriver.session();
		Transaction transaction = session.beginTransaction();

		Map<String, Object> paramMap = new HashMap<>();
		paramMap.put("data", relationMap);

		try {

			Statement statement = new Statement("match (node:Validation{identifier:'" + startNodeId
					+ "'})-[r]->(node1:Validation{identifier:'" + endNodeId + "'}) set r=$data", paramMap);

			statement = new Statement(
					"match (node:Validation{identifier:'" + startNodeId + "'}), (node1:Validation{identifier:'"
							+ endNodeId + "'}) create (node)-[rel:traversal]->(node1) set rel=$data",
					paramMap);

			transaction.run(statement);

			transaction.commitAsync().toCompletableFuture().get();
		} catch (Exception e) {
			transaction.rollbackAsync().toCompletableFuture().get();
		} finally {
			transaction.close();
			session.close();
		}
	}

	public void validations(Paths paths, Map<String, Object> contentMeta, Node startNode,
			Set<String> validatedProperties, List<Map<String, Object>> toValidateMaps)
			throws JsonParseException, JsonMappingException, IOException {

		ListValue validationsBelows = (ListValue) startNode.get("validateBelow");

		for (Object validateBelow : validationsBelows.asList()) {
			Node endNode = paths.getEndNode(startNode,
					validateBelow.toString() + "_" + contentMeta.get(validateBelow.toString()));

			if (endNode != null) {
				validations(paths, contentMeta, endNode, validatedProperties, toValidateMaps);
			}
		}

		List<Map<String, Object>> validateMaps = objectMapper.readValue(
				startNode.asMap().get("validateHere").toString(), new TypeReference<List<Map<String, Object>>>() {
				});

		// pass the end(leaf) node "validationsHere" stringified Json to function
		validate(validateMaps, contentMeta, validatedProperties, toValidateMaps);
	}

	public void validate(List<Map<String, Object>> validateMaps, Map<String, Object> contentMeta, Set<String> validated,
			List<Map<String, Object>> toValidateMaps) {

		// function checks if the provided property has already been validated before
		// if not sends to another function multiple validations on same property is
		// allowed for validated here of one node, it will be ignored for other nodes.
		Set<String> validatedProperties = new HashSet<>();

		for (Map<String, Object> validateMap : validateMaps) {
			if (!validated.contains(validateMap.get("property").toString())) {
				toValidateMaps.add(validateMap);
				validatedProperties.add(validateMap.get("property").toString());
			}
		}
		// add validated properties after validations
		validated.addAll(validatedProperties);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, Set<String>> validationsV2(String rootOrg, Map<String, Object> contentMeta) throws Exception {

		Map<String, Set<String>> errors = new HashMap<>();
		Paths paths = getHierarchy();
		Node startNode = paths.getNodeById("val_node_" + rootOrg.toLowerCase());

		Queue<Map<String, Object>> contentQueue = new LinkedList<>();
		contentQueue.add(contentMeta);
		while (!contentQueue.isEmpty()) {
			// reqd for recursive func. no use here.
			Set<String> validatedProperties = new HashSet<>();
			List<Map<String, Object>> toValidateMaps = new ArrayList<>();

			Map<String, Object> contentToValidate = contentQueue.poll();
			List<Map<String, Object>> children = (List<Map<String, Object>>) contentToValidate
					.get(LexConstants.CHILDREN);
			if (children != null && !children.isEmpty()) {
				contentQueue.addAll(children);
			}
			validations(paths, contentToValidate, startNode, validatedProperties, toValidateMaps);
			Set<String> contentErrors = new HashSet<>();

			for (Map<String, Object> validateMap : toValidateMaps) {
				ValidatorV2.validate(contentToValidate, validateMap, contentErrors);
				if (contentErrors != null && !contentErrors.isEmpty()) {
					errors.put(contentToValidate.get(LexConstants.IDENTIFIER).toString(), contentErrors);
				}
			}
		}
		return errors;
	}

	public Paths getHierarchy() {

		List<Path> paths = new ArrayList<>();

		try (Session session = neo4jDriver.session()) {
			Statement statement = new Statement("MATCH (n:Validation)-[r]->(n1:Validation) return n,r,n1");
			StatementResult result = session.run(statement);

			while (result.hasNext()) {
				Record record = result.next();
				Node startNode = record.get("n").asNode();
				Relationship relation = record.get("r").asRelationship();
				Node endNode = record.get("n1").asNode();
				paths.add(new Path(startNode, endNode, relation));
			}
			return new Paths(paths);
		}
	}

	@Override
	public void validateMetaFields(String rootOrg, Map<String, Object> contentMeta) throws Exception {
		// all valid fields for given root_org
		Set<String> validFields = getRequiredFieldsForRootOrg(rootOrg);

		// used to validate whether the fields given in request are valid or not
		for (String metaKey : contentMeta.keySet()) {
			if (!validFields.contains(metaKey)) {
				throw new BadRequestException("Invalid meta field " + metaKey);
			}
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public Set<String> getRequiredFieldsForRootOrg(String rootOrg) throws Exception {

		Session session = neo4jDriver.session();

		List<String> validFields = new ArrayList<>();

		Statement statement = new Statement(
				"match (node:Validation{identifier:'val_" + rootOrg.toLowerCase() + "_node'}) return node");

		StatementResult result = session.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty() || records.size() > 1) {
			throw new Exception("zero or multiple validation node found for " + rootOrg);
		}

		Record record = records.get(0);

		validFields = (List<String>) record.get("node").asNode().asMap().get("validFields");

		session.close();
		return new HashSet<>(validFields);
	}

	/**
	 * converts unModifieableMap returned from neo4j to HashMap.
	 * 
	 * @param unModifieableMap
	 * @return
	 */
	public static Map<String, Object> convertToHashMap(Map<String, Object> unModifieableMap) {

		Map<String, Object> contentMeta = new HashMap<>();

		unModifieableMap.entrySet().forEach(entry -> contentMeta.put(entry.getKey(), entry.getValue()));

		return contentMeta;
	}

}
