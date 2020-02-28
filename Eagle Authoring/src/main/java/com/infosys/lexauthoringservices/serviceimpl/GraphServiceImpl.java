/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.serviceimpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.model.DeleteRelationRequest;
import com.infosys.lexauthoringservices.model.UpdateMetaRequest;
import com.infosys.lexauthoringservices.model.UpdateRelationRequest;
import com.infosys.lexauthoringservices.model.neo4j.ContentNode;
import com.infosys.lexauthoringservices.service.GraphService;
import com.infosys.lexauthoringservices.util.GraphUtil;

@Service
public class GraphServiceImpl implements GraphService {

	private ObjectMapper mapper = new ObjectMapper();

	@Override
	public void createNodeV2(String rootOrg, Map<String, Object> contentMeta, Transaction transaction)
			throws Exception {

		GraphUtil.mapParser(contentMeta, false);

		Map<String, Object> params = new HashMap<>();
		params.put("data", contentMeta);

		Statement statement = new Statement("create (node:" + rootOrg + " $data) return node", params);

		StatementResult result = transaction.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty()) {
			throw new Exception("Something went wrong");
		}
	}

	// to be removed after migration
	public void createNodeV2(String rootOrg, Map<String, Object> contentMeta, Session session) throws Exception {

		contentMeta = GraphUtil.mapParser(contentMeta, false);

		Map<String, Object> params = new HashMap<>();
		params.put("data", contentMeta);

		Statement statement = new Statement("create (node:" + rootOrg + " $data) return node", params);

		StatementResult result = session.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty()) {
			throw new Exception("Something went wrong");
		}
	}

	@Override
	public void createFeatureNode(String rootOrg, Map<String, Object> contentMeta, Transaction transaction)
			throws Exception {

		GraphUtil.mapParser(contentMeta, false);
		System.out.println(contentMeta);
		Map<String, Object> params = new HashMap<>();
		params.put("data", contentMeta);

		Statement statement = new Statement("create (node:Feature_" + rootOrg + " $data) return node", params);
		System.out.println(statement);
		StatementResult result = transaction.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty()) {
			throw new Exception("Something went wrong");
		}
	}

	@Override
	public void createNodes(String rootOrg, List<Map<String, Object>> contentMetas, Transaction transaction)
			throws Exception {

		if (contentMetas == null || contentMetas.isEmpty()) {
			return;
		}

		GraphUtil.mapParser(contentMetas, false);

		Map<String, Object> paramMap = new HashMap<>();
		paramMap.put("createRequests", contentMetas);

		Statement statement = new Statement(
				"unwind $createRequests as data create (node:" + rootOrg + ") with node,data set node=data return node",
				paramMap);

		StatementResult statementResult = transaction.run(statement);

		List<Record> records = statementResult.list();
		if (records == null || records.size() == 0) {
			throw new Exception("Bulk create operation failed");
		}
	}

	// to be removed after migration
	public void createNodes(String rootOrg, List<Map<String, Object>> contentMetas, Session session) throws Exception {

		if (contentMetas == null || contentMetas.isEmpty()) {
			return;
		}

		GraphUtil.mapParser(contentMetas, false);

		Map<String, Object> paramMap = new HashMap<>();
		paramMap.put("createRequests", contentMetas);

		Statement statement = new Statement(
				"unwind $createRequests as data create (node:" + rootOrg + ") with node,data set node=data return node",
				paramMap);

		StatementResult statementResult = session.run(statement);

		List<Record> records = statementResult.list();
		if (records == null || records.size() == 0) {
			throw new Exception("Bulk create operation failed");
		}
	}

	@Override
	public ContentNode updateNodeV2(String rootOrg, String identifier, Map<String, Object> contentMetaToUpdate,
			Transaction transaction) throws Exception {

		GraphUtil.mapParser(contentMetaToUpdate, false);

		Map<String, Object> paramMap = new HashMap<>();
		paramMap.put("data", contentMetaToUpdate);

		Statement statement = new Statement("match (node) where  node.identifier='" + identifier + "' and (node:"
				+ rootOrg + " or node:Shared) set node+= $data return node", paramMap);

		StatementResult statementResult = transaction.run(statement);
		List<Record> records = statementResult.list();

		if (records == null || records.size() == 0) {
			throw new Exception("Update operation failed");
		}

		return GraphUtil.createContentNode(records);
	}

	@Override
	public void updateNodesV2(String rootOrg, List<UpdateMetaRequest> updateMetaRequests, Transaction transaction)
			throws Exception {

		if (updateMetaRequests == null || updateMetaRequests.isEmpty()) {
			return;
		}

		List<Map<String, Object>> updateRequests = new ArrayList<>();
		for (UpdateMetaRequest updateMetaRequest : updateMetaRequests) {
			updateMetaRequest.setMetaData(GraphUtil.mapParser(updateMetaRequest.getMetaData(), false));
			updateRequests.add(mapper.convertValue(updateMetaRequest, new TypeReference<Map<String, Object>>() {
			}));
		}

		Map<String, Object> paramMap = new HashMap<>();
		paramMap.put("updateMeta", updateRequests);
		
		
		Statement statement = new Statement(
				"unwind $updateMeta as data optional match (n:" + rootOrg + "{identifier:data.identifier}) with n,data "
						+ "optional match (n1:Shared{identifier:data.identifier}) with " + "case "
						+ "when n is not NULL " + "then n when n1 is not NULL "
						+ "then n1  end as startNode,data set startNode+=data.metaData return startNode ",
				paramMap);

		StatementResult statementResult = transaction.run(statement);
		List<Record> records = statementResult.list();

		if (records == null || records.size() == 0) {
			throw new Exception("Update operation failed");
		}
	}

	@Override
	public ContentNode getNodeByUniqueIdV2(String rootOrg, String identifier, Transaction transaction)
			throws Exception {

		Statement statement = new Statement("match (node) where node.identifier='" + identifier + "' and (node:"
				+ rootOrg + " or node:Shared) with node optional match (node)-[childRelation:Has_Sub_Content]->(child) "
				+ "where child:" + rootOrg + " or child:Shared with node,child,childRelation "
				+ "optional match (parent)-[parentRelation:Has_Sub_Content]->(node)  where parent:" + rootOrg
				+ " or parent:Shared return node,childRelation,child,parentRelation,parent");

		StatementResult result = transaction.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty()) {
			throw new Exception("IDENTIFIER NOT FOUND");
		}

		ContentNode contentNode = GraphUtil.createContentNode(records);
		return contentNode;
	}

	@Override
	public ContentNode getNodeByUniqueIdV3(String rootOrg, String identifier, Transaction transaction)
			throws Exception {

		Statement statement = new Statement("match (node) where node.identifier='" + identifier + "' and (node:"
				+ rootOrg + " or node:Shared) with node optional match (node)-[childRelation:Has_Sub_Content]->(child) "
				+ "where child:" + rootOrg + " or child:Shared with node,child,childRelation "
				+ "optional match (parent)-[parentRelation:Has_Sub_Content]->(node)  where parent:" + rootOrg
				+ " or parent:Shared return node,childRelation,child,parentRelation,parent");

		StatementResult result = transaction.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty()) {
			return null;
		}

		ContentNode contentNode = GraphUtil.createContentNode(records);
		return contentNode;
	}

	@Override
	public List<ContentNode> getNodesByUniqueIdV2(String rootOrg, List<String> identifiers, Transaction transaction)
			throws Exception {

		identifiers = identifiers.stream().map(identifier -> "'" + identifier + "'").collect(Collectors.toList());

		Statement statement = new Statement(" match (node) where node.identifier in " + identifiers + " and (node:"
				+ rootOrg + " or node:Shared) with node optional match (node)-[childRelation:Has_Sub_Content]->(child) "
				+ "where child:" + rootOrg + " or child:Shared with node,child,childRelation "
				+ "optional match (parent)-[parentRelation:Has_Sub_Content]->(node)  where parent:" + rootOrg
				+ " or parent:Shared return node,childRelation,child,parentRelation,parent");

		StatementResult result = transaction.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty()) {
			return new ArrayList<>();
		}
		return GraphUtil.createContentNodes(records);
	}

	@Override
	public void deleteChildren(String rootOrg, List<String> identifiers, Transaction transaction) {

		identifiers = identifiers.stream().map(identifier -> "'" + identifier + "'").collect(Collectors.toList());

		Statement statement = new Statement("unwind " + identifiers
				+ " as data match (node) where node.identifier=data and (node:" + rootOrg
				+ " or node:Shared) with node match (node)-[childRelation:Has_Sub_Content]->(child) delete childRelation");
		transaction.run(statement);

	}

	@Override
	public List<ContentNode> getNodesByUniqueIdV2WithoutRelations(String rootOrg, List<String> identifiers,
			Transaction transaction) throws Exception {

		identifiers = identifiers.stream().map(identifier -> "'" + identifier + "'").collect(Collectors.toList());

		Statement statement = new Statement("match (node) where node.identifier in " + identifiers + " and (node:"
				+ rootOrg + " or node:Shared) return node");

		StatementResult result = transaction.run(statement);
		List<Record> records = result.list();

		if (records == null || records.isEmpty()) {
			return new ArrayList<>();
		}
		return GraphUtil.createContentNodes(records);
	}

	public static void main(String args[]) throws Exception {

		Driver driver = GraphDatabase.driver("bolt://URL_DEFAULT:7687");
		Session session = driver.session();
		Transaction transaction = session.beginTransaction();

		List<ContentNode> contentNodes = new GraphServiceImpl().getNodesByUniqueIdV2WithoutRelations("Infosys",
				Arrays.asList("lex_auth_012793546829709312394"), transaction);
		transaction.commitAsync().toCompletableFuture().get();
		session.close();

		System.out.println(contentNodes.get(0).getMetadata());

	}

	@Override
	public void updateFeatureNodesV2(String rootOrg, List<UpdateMetaRequest> updateMetaRequests,
			Transaction transaction) throws Exception {

		if (updateMetaRequests == null || updateMetaRequests.isEmpty()) {
			return;
		}

		List<Map<String, Object>> updateRequests = new ArrayList<>();
		for (UpdateMetaRequest updateMetaRequest : updateMetaRequests) {
			updateMetaRequest.setMetaData(GraphUtil.mapParser(updateMetaRequest.getMetaData(), false));
			updateRequests.add(mapper.convertValue(updateMetaRequest, new TypeReference<Map<String, Object>>() {
			}));
		}

		Map<String, Object> paramMap = new HashMap<>();
		paramMap.put("updateMeta", updateRequests);
		Statement statement = new Statement(
				"unwind $updateMeta as data match (node) where node.identifier=data.identifier and (node:Feature_"
						+ rootOrg + ") set node+=data.metaData return node",
				paramMap);
		StatementResult statementResult = transaction.run(statement);
		List<Record> records = statementResult.list();

		if (records == null || records.size() == 0) {
			throw new Exception("Update operation failed");
		}
	}

	@Override
	public void createRelation(String rootOrg, String startNodeId, String endNodeId, Integer index,
			Transaction transaction) throws Exception {

		Statement statement = new Statement("match (startNode),(endNode) where startNode.identifier='" + startNodeId
				+ "' and endNode.identifier='" + endNodeId + "' and (startNode:" + rootOrg
				+ " or startNode:Shared) and (endNode:" + rootOrg + " or endNode:Shared)");

		StatementResult statementResult = transaction.run(statement);

		List<Record> records = statementResult.list();
		if (records == null || records.size() == 0) {
			throw new Exception("Create relation operation failed");
		}
	}

	@Override
	public void createRelations(String rootOrg, List<UpdateRelationRequest> updateRelationRequests,
			Transaction transaction) {

		if (updateRelationRequests == null || updateRelationRequests.isEmpty()) {
			return;
		}

		List<Map<String, Object>> updateRequests = new ArrayList<>();
		for (UpdateRelationRequest updateRelationRequest : updateRelationRequests) {
			updateRequests.add(mapper.convertValue(updateRelationRequest, new TypeReference<Map<String, Object>>() {
			}));
		}

		Map<String, Object> params = new HashMap<>();
		params.put("updateRelation", updateRequests);

		Statement statement = new Statement("unwind $updateRelation as data " + "optional match (n:" + rootOrg
				+ "{identifier:data.startNodeId}) with n,data "
				+ "optional match (n1:Shared{identifier:data.startNodeId}) with " + "case " + "when n is not NULL "
				+ "then n " + "when n1 is not NULL " + "then n1 " + "end as startNode,data " + "optional match (n:"
				+ rootOrg + "{identifier:data.endNodeId}) with startNode,n,data "
				+ "optional match (n1:Shared{identifier:data.endNodeId}) with " + "case " + "when n is not NULL "
				+ "then n " + "when n1 is not NULL " + "then n1 " + "end as endNode,startNode,data "
				+ "create (startNode)-[r:Has_Sub_Content{index:data.index}]->(endNode) "
				+ "return startNode.identifier,endNode.identifier,data.index", params);

		transaction.run(statement);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void deleteRelations(String rootOrg, List<DeleteRelationRequest> deleteRelationRequests,
			Transaction transaction) {

		List<Map<String, Object>> deleteRequests = new ArrayList<>();
		for (DeleteRelationRequest deleteRelationRequest : deleteRelationRequests) {
			deleteRequests.add(mapper.convertValue(deleteRelationRequest, Map.class));
		}

		Map<String, Object> params = new HashMap<>();
		params.put("deleteRelation", deleteRequests);

		Statement statement = new Statement(
				"unwind $deleteRelation as data match(startNode)-[r:Has_Sub_Content]->(endNode) " + "where"
						+ "(startNode:" + rootOrg + " or startNode:Shared) and (endNode:" + rootOrg
						+ " or endNode:Shared) and "
						+ "startNode.identifier=data.startNodeId and endNode.identifier=data.endNodeId delete r",
				params);
		transaction.run(statement);

	}

	@Override
	public void createChildRelations(String rootOrg, String startNodeId, List<Map<String, Object>> updateRequests,
			Transaction transaction) {

		Map<String, Object> params = new HashMap<>();
		params.put("updateRelation", updateRequests);

		Statement statement = new Statement(
				"unwind $updateRelation as data match(startNode:" + rootOrg + "{identifier:'" + startNodeId
						+ "'}) , (endNode{identifier:data.endNodeId}) where endNode:Shared or endNode:" + rootOrg
						+ " create (startNode)-[r:Has_Sub_Content{index:data.index}]->(endNode) return r",
				params);
		transaction.run(statement);

	}

	@Override
	public void createParentRelations(String rootOrg, String endNodeId, List<Map<String, Object>> updateRequests,
			Transaction transaction) {

		Map<String, Object> params = new HashMap<>();
		params.put("updateRelation", updateRequests);

		Statement statement = new Statement("unwind $updateRelation as data match(endNode:" + rootOrg + "{identifier:'"
				+ endNodeId + "'}) , (startNode:" + rootOrg
				+ "{identifier:data.startNodeId}) create (startNode)-[r:Has_Sub_Content{index:data.index}]->(endNode) return r",
				params);

		transaction.run(statement);

	}

	@Override
	public void deleteRelation(String rootOrg, String startNodeId, List<String> endNodeIds, Transaction transaction) {

		endNodeIds = endNodeIds.stream().map(endNodeId -> "'" + endNodeId + "'").collect(Collectors.toList());

		Statement statement = new Statement(
				"unwind " + endNodeIds + " as data match (startNode:" + rootOrg + "{identifier:'" + startNodeId
						+ "'})-[r:Has_Sub_Content]->(endNode:" + rootOrg + "{identifier:data}) delete r");

		transaction.run(statement);
	}

	@Override
	public void deleteNodes(String rootOrg, List<String> identifiers, Transaction transaction) {

		identifiers = identifiers.stream().map(identifier -> "'" + identifier + "'").collect(Collectors.toList());

		Statement statement = new Statement(
				"unwind " + identifiers + " as data match (node:" + rootOrg + "{identifier:data}) detach delete node");

		transaction.run(statement);
	}

	@Override
	public void deleteFeatureNodes(String rootOrg, Set<String> identifiers, Transaction transaction) {

		identifiers = identifiers.stream().map(identifier -> "'" + identifier + "'").collect(Collectors.toSet());

		Statement statement = new Statement("unwind " + identifiers + " as data match (node:Feature_" + rootOrg
				+ "{identifier:data}) detach delete node");

		transaction.run(statement);
	}
}
