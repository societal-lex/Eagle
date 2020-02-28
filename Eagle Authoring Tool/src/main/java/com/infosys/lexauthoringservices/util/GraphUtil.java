/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.types.Node;
import org.neo4j.driver.v1.types.Relationship;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.model.UpdateRelationRequest;
import com.infosys.lexauthoringservices.model.neo4j.ContentNode;
import com.infosys.lexauthoringservices.model.neo4j.Relation;

public class GraphUtil {

	// fields to be serialized and de-serialized while storing and fetching from
	// neo4j.
	public static final List<String> fieldsToParse = Arrays.asList(LexConstants.COMMENTS,
			LexConstants.CERTIFICATION_LIST, LexConstants.PLAYGROUND_RESOURCES, LexConstants.SOFTWARE_REQUIREMENTS,
			LexConstants.SYSTEM_REQUIREMENTS, LexConstants.REFERENCES, LexConstants.CREATOR_CONTACTS,
			LexConstants.CREATOR_DEATILS, LexConstants.PUBLISHER_DETAILS, LexConstants.PRE_CONTENTS,
			LexConstants.POST_CONTENTS, LexConstants.CATALOG, LexConstants.CLIENTS, LexConstants.SKILLS,
			LexConstants.K_ARTIFACTS, LexConstants.TRACK_CONTACT_DETAILS, LexConstants.ORG,
			LexConstants.SUBMITTER_DETAILS, LexConstants.CONCEPTS, LexConstants.PLAG_SCAN, LexConstants.TAGS,
			"eligibility", "scoreType", "externalData", "verifiers", "verifier", "subTitles", "roles", "group",
			"msArtifactDetails", "studyMaterials", "equivalentCertifications",LexConstants.TRANSCODING);

	public static ContentNode createContentNode(List<Record> records)
			throws JsonParseException, JsonMappingException, IOException {
		ContentNode contentNode = new ContentNode();

		Set<Relationship> childRelations = new HashSet<>();
		Set<Relationship> parentRelations = new HashSet<>();

		for (Record record : records) {

			Node nodeFetched = record.get(LexConstants.NODE).asNode();
			String rootOrg = nodeFetched.labels().iterator().next();

			contentNode.setId(nodeFetched.id());
			contentNode.setRootOrg(rootOrg);
			contentNode.setIdentifier(nodeFetched.asMap().get(LexConstants.IDENTIFIER).toString());
			contentNode.setMetadata(convertToHashMap(nodeFetched.asMap()));

			createChildRelation(contentNode, childRelations, record);
			createParentRelation(contentNode, parentRelations, record);
		}

		contentNode.setMetadata(mapParser(contentNode.getMetadata(), true));
		return contentNode;
	}

	public static List<ContentNode> createContentNodes(List<Record> records)
			throws JsonParseException, JsonMappingException, IOException {
		List<ContentNode> contentNodes = new ArrayList<>();

		Node prevNode = records.get(0).get(LexConstants.NODE).asNode();
		List<Record> recordsPerContent = new ArrayList<>();

		for (Record record : records) {

			if (!prevNode.equals(record.get(LexConstants.NODE).asNode())) {
				contentNodes.add(createContentNode(recordsPerContent));
				recordsPerContent = new ArrayList<>();
				prevNode = record.get(LexConstants.NODE).asNode();
			}
			recordsPerContent.add(record);
		}

		contentNodes.add(createContentNode(recordsPerContent));
		return contentNodes;
	}

	public static void createChildRelation(ContentNode contentNode, Set<Relationship> childRelations, Record record) {

		if (!record.get(LexConstants.CHILD).isNull() && !record.get(LexConstants.CHILD_RELATION).isNull()) {

			Node childNode = record.get(LexConstants.CHILD).asNode();
			Relationship childRelation = record.get(LexConstants.CHILD_RELATION).asRelationship();

			if (childRelation != null && !childRelations.contains(childRelation)) {
				Relation relation = new Relation();

				relation.setId(childRelation.id());
				relation.setMetadata(childRelation.asMap());

				relation.setStartNodeId(contentNode.getIdentifier());
				relation.setStartNodeMetadata(contentNode.getMetadata());

				relation.setEndNodeId(childNode.asMap().get(LexConstants.IDENTIFIER).toString());
				relation.setEndNodeMetadata(childNode.asMap());

				relation.setRelationType("Has_Sub_Content");

				contentNode.getChildren().add(relation);
				childRelations.add(childRelation);
			}
		}
	}

	public static void createParentRelation(ContentNode contentNode, Set<Relationship> parentRelations, Record record) {
		if (!record.get(LexConstants.PARENT).isNull() && !record.get(LexConstants.PARENT_RELATION).isNull()) {

			Node parentNode = record.get(LexConstants.PARENT).asNode();
			Relationship parentRelation = record.get(LexConstants.PARENT_RELATION).asRelationship();

			if (parentRelation != null && !parentRelations.contains(parentRelation)) {
				Relation relation = new Relation();

				relation.setId(parentRelation.id());
				relation.setMetadata(parentRelation.asMap());

				relation.setStartNodeId(parentNode.asMap().get(LexConstants.IDENTIFIER).toString());
				relation.setStartNodeMetadata(parentNode.asMap());

				relation.setEndNodeId(contentNode.getIdentifier());
				relation.setEndNodeMetadata(contentNode.getMetadata());

				relation.setRelationType("Has_Sub_Content");

				contentNode.getParents().add(relation);
				parentRelations.add(parentRelation);
			}
		}
	}

	public static List<Map<String, Object>> mapParser(List<Map<String, Object>> contentMetas, boolean toMap)
			throws JsonParseException, JsonMappingException, IOException {

		for (Map<String, Object> contentMeta : contentMetas) {
			mapParser(contentMeta, false);
		}
		return contentMetas;
	}

	public static Map<String, Object> mapParser(Map<String, Object> contentMeta, boolean toMap)
			throws JsonParseException, JsonMappingException, IOException {

		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> updatedContentMeta = contentMeta;

		for (String fieldToParse : fieldsToParse) {

			if (updatedContentMeta.containsKey(fieldToParse) && updatedContentMeta.get(fieldToParse) != null
					&& !updatedContentMeta.get(fieldToParse).toString().isEmpty()) {
				if (toMap) {
					String fieldValue = updatedContentMeta.get(fieldToParse).toString();
					Object fieldDeserialized = mapper.readValue(fieldValue, Object.class);
					updatedContentMeta.put(fieldToParse, fieldDeserialized);
				} else if (!toMap) {
					Object fieldSerialized = updatedContentMeta.get(fieldToParse);
					updatedContentMeta.put(fieldToParse, mapper.writeValueAsString(fieldSerialized));
				}
			}
		}
		return updatedContentMeta;
	}

	public static List<UpdateRelationRequest> createUpdateRelationRequestsForImageNodes(
			List<ContentNode> imageNodesToBeCreated) {

		List<UpdateRelationRequest> updateRelationRequests = new ArrayList<>();

		// creating relations for the imageNodes.
		for (ContentNode imageNodeToBeCreated : imageNodesToBeCreated) {

			for (Relation childRelation : imageNodeToBeCreated.getChildren()) {

				updateRelationRequests.add(
						new UpdateRelationRequest(imageNodeToBeCreated.getIdentifier(), childRelation.getEndNodeId(),
								Integer.parseInt(childRelation.getMetadata().get(LexConstants.INDEX).toString())));
			}

			for (Relation parentRelation : imageNodeToBeCreated.getParents()) {

				updateRelationRequests.add(
						new UpdateRelationRequest(parentRelation.getStartNodeId(), imageNodeToBeCreated.getIdentifier(),
								Integer.parseInt(parentRelation.getMetadata().get(LexConstants.INDEX).toString())));
			}
		}

		return updateRelationRequests;
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
