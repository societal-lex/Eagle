/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Transaction;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.serviceimpl.GraphServiceImpl;
import com.infosys.lexauthoringservices.util.LexConstants;

public class Migration {

	RestTemplate restTemplate = new RestTemplate();
	ObjectMapper mapper = new ObjectMapper();
	GraphServiceImpl graphService = new GraphServiceImpl();
	Driver driver = GraphDatabase.driver("bolt://URL_DEFAULT:7687");
	Integer batchSize = 20;
	ExecutorService executorService = Executors.newFixedThreadPool(30);

	String rootOrg = "InfosysMigrationTest";

	private static final List<String> fieldsToParse = Arrays.asList(LexConstants.COMMENTS,
			LexConstants.CERTIFICATION_LIST, LexConstants.PLAYGROUND_RESOURCES, LexConstants.SOFTWARE_REQUIREMENTS,
			LexConstants.SYSTEM_REQUIREMENTS, LexConstants.REFERENCES, LexConstants.CREATOR_CONTACTS,
			LexConstants.CREATOR_DEATILS, LexConstants.PUBLISHER_DETAILS, LexConstants.PRE_CONTENTS,
			LexConstants.POST_CONTENTS, LexConstants.TAGS, LexConstants.CLIENTS, LexConstants.SKILLS,
			LexConstants.TRACK, LexConstants.K_ARTIFACTS, LexConstants.TRACK_CONTACT_DETAILS, LexConstants.ORG,
			LexConstants.SUBMITTER_DETAILS);

	public static final int scrollStateTime = 300000;

	public static RestHighLevelClient restClient() {

		final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();

		credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("UNAME", "PASSWORD"));
		RestClientBuilder builder = RestClient.builder(new HttpHost("URL_DEFAULT", Integer.parseInt("5903")))
				.setHttpClientConfigCallback(
						httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));

		RestHighLevelClient client = new RestHighLevelClient(builder);
		return client;
	}

	private void createNodes() throws Exception {
		RestHighLevelClient client = restClient();
		SearchRequest searchRequest = new SearchRequest();
		searchRequest.indices("lexcontentindex").types("resource").scroll(new TimeValue(scrollStateTime));
		SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
		searchSourceBuilder.query(QueryBuilders.matchAllQuery());
		searchSourceBuilder.size(batchSize);
		searchRequest.source(searchSourceBuilder);
		SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
		List<Map<String, Object>> contentMaps = new ArrayList<>();
		while (response.getHits().getHits().length > 0) {
			for (SearchHit searchHit : response.getHits()) {
				Map<String, Object> source = searchHit.getSourceAsMap();
				contentMaps.add(source);
			}
			System.out.println("fetched " + batchSize + " nodes");
			createContentInAuthTool(contentMaps);
			contentMaps.clear();
			String scrollId = response.getScrollId();
			SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
			scrollRequest.scroll(new TimeValue(scrollStateTime));
			response = client.scroll(scrollRequest, RequestOptions.DEFAULT);
		}
		// create here also
		createContentInAuthTool(contentMaps);
		System.out.println("content created in neo4j");

	}

	private void createHierarchy() throws IOException, InterruptedException, ExecutionException {

		FileWriter fw = new FileWriter("D:\\invalidContent.txt");
		FileWriter createdWriter = new FileWriter("D:\\created.txt");
		FileWriter idsCreated = new FileWriter("D:\\skippedHierarchy.txt");

		RestHighLevelClient client = restClient();
		SearchRequest searchRequest = new SearchRequest();
		searchRequest.indices("lexcontentindex").types("resource").scroll(new TimeValue(scrollStateTime));
		SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
		searchSourceBuilder.query(QueryBuilders.matchAllQuery());
		searchSourceBuilder.size(batchSize);
		searchRequest.source(searchSourceBuilder);
		SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
		List<Map<String, Object>> contentMaps = new ArrayList<>();
		while (response.getHits().getHits().length > 0) {
			for (SearchHit searchHit : response.getHits()) {
				Map<String, Object> source = searchHit.getSourceAsMap();
				contentMaps.add(source);
			}
			System.out.println("fetched " + batchSize + " nodes for hierarchy creation");
			createHierarchyUtil(contentMaps, fw, createdWriter, idsCreated);
			contentMaps.clear();
			String scrollId = response.getScrollId();
			SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
			scrollRequest.scroll(new TimeValue(scrollStateTime));
			response = client.scroll(scrollRequest, RequestOptions.DEFAULT);
		}
		createHierarchyUtil(contentMaps, fw, createdWriter, idsCreated);
		fw.close();
		System.out.println("content hierarchy finished");
	}

	@SuppressWarnings("unchecked")
	private void createHierarchyUtil(List<Map<String, Object>> contentMaps, FileWriter fw, FileWriter createdWriter,
			FileWriter idsCreated) throws IOException, InterruptedException, ExecutionException {

		List<CompletableFuture<String>> futures = new ArrayList<>();
		for (Map<String, Object> contentMap : contentMaps) {

			if (!contentMap.containsKey("identifier")) {
				continue;
			}

//			if (idsToSkip.contains(contentMap.get("identifier"))) {
//				System.out.println("skipping " + contentMap.get("identifier"));
//				continue;
//			}
			String identifier = contentMap.get("identifier").toString();
			List<Map<String, Object>> children = (List<Map<String, Object>>) contentMap.get("children");
			if (children == null || children.isEmpty()) {
				if (!contentMap.containsKey("contentType")) {
					fw.write("invalid content " + mapper.writeValueAsString(contentMap) + "\n");
					continue;
				}
				if (!contentMap.get("contentType").equals("Resource")) {
					if (!contentMap.containsKey("isExternal") || !contentMap.get("isExternal").equals("Yes")) {
						fw.write("possible invalid content " + mapper.writeValueAsString(contentMap) + "\n");
					}
				} else if (contentMap.get("contentType").equals("Resource")) {
					fw.write("skipping content for resource " + mapper.writeValueAsString(contentMap) + "\n");
				}
				continue;
			}
			List<String> childrenIds = new ArrayList<>();
			try {
				childrenIds = children.stream().map(child -> child.get("identifier").toString())
						.collect(Collectors.toList());
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("----------------------------------------Error caught here");
				System.out.println(contentMap.toString());
				continue;
			}

			Map<String, Object> requestBody = new HashMap<>();
			Map<String, Object> nodesModified = new HashMap<>();
			requestBody.put("nodesModified", nodesModified);

			Map<String, Object> hierarchy = new HashMap<>();
			Map<String, Object> contentHierarchy = new HashMap<>();
			contentHierarchy.put("root", true);
			contentHierarchy.put("children", childrenIds);
			hierarchy.put(identifier, contentHierarchy);
			requestBody.put("hierarchy", hierarchy);

			futures.add(CompletableFuture.supplyAsync(() -> {

				String url = "http://URL_DEFAULT:4011/action/content/hierarchy/update?rootOrg=" + rootOrg
						+ "&migration=yes";
				try {
					restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(requestBody), String.class);
					return identifier;
				} catch (HttpStatusCodeException ex) {
					System.out.println(ex.getStatusCode() + " " + ex.getResponseBodyAsString());
					try {
						fw.write("could not create hierarchy for " + requestBody + " " + ex.getStatusCode() + " "
								+ ex.getResponseBodyAsString());
					} catch (IOException e) {
						System.out.println("could not log");
					}
					return null;
				}
			}));
		}
		if (!futures.isEmpty()) {
			System.out.println("sent request for hierarchy");
		}
		for (CompletableFuture<String> future : futures) {
			String idCreated = future.get();
			System.out.println("created hierarchy for " + mapper.writeValueAsString(idCreated));
			createdWriter.write("\"" + idCreated + "\",");
			createdWriter.flush();

		}
		if (!futures.isEmpty()) {
			System.out.println("request completed for hierarchy");
		}

		System.out
				.println("-------------------------------------------------------------------------------------------");
		System.out
				.println("-------------------------------------------------------------------------------------------");
	}

	private void createContentInAuthTool(List<Map<String, Object>> contentMaps) throws Exception {

		Iterator<Map<String, Object>> iterator = contentMaps.iterator();

		while (iterator.hasNext()) {
			Map<String, Object> contentMap = iterator.next();
			populateMetaForCreation(contentMap);
			if (!contentMap.containsKey(LexConstants.IDENTIFIER)) {
				iterator.remove();
			}
		}
		Session session = driver.session();
		try {
			graphService.createNodes(rootOrg, new ArrayList<>(contentMaps), session);
			System.out.println("created " + batchSize + " nodes");
		} catch (Exception e) {
			System.out.println("failed create operation");
			determineFailueCausingField(contentMaps, session);
		}
		session.close();
	}

	private void determineFailueCausingField(List<Map<String, Object>> contentMaps, Session session) {

		System.out.println("retrying create opertations");
		Map<String, Object> errorMap = new HashMap<>();
		for (Map<String, Object> contentMap : contentMaps) {
			try {
				graphService.createNodeV2(rootOrg, new HashMap<>(contentMap), session);
			} catch (Exception e) {
				e.printStackTrace();
				errorMap = contentMap;
			}
		}

		System.out.println("failed because of " + errorMap);

		for (Map.Entry<String, Object> entry : errorMap.entrySet()) {

			if (entry.getValue() instanceof Map
					|| entry.getValue() instanceof List && !fieldsToParse.contains(entry.getKey())) {
				System.out.println(entry.getKey());
			}
		}
	}

	private void populateMetaForCreation(Map<String, Object> contentMap) {
		// contentMap.remove("msArtifactDetails");
		contentMap.remove("comments");
		contentMap.remove("publisherDetails");
		contentMap.remove("children");
		contentMap.remove("collections");
		contentMap.remove("topics");
		contentMap.put("status", "Draft");
	}

	private void populateMetaForImageNodeCreation(Map<String, Object> contentMap) {
		// contentMap.remove("msArtifactDetails");
		contentMap.remove("comments");
		contentMap.remove("publisherDetails");
		contentMap.remove("children");
		contentMap.remove("collections");
		contentMap.remove("topics");
		contentMap.remove("contentType");
		contentMap.remove("mimeType");
		contentMap.remove("identifier");
		contentMap.remove("status");
	}

	private void createContentFromAuthIndex() throws Exception {

		List<String> contentTypesToFetch = Arrays.asList("Resource", "Collection", "Course", "Learning Path");

		RestHighLevelClient client = restClient();

		for (String contentTypeToFetch : contentTypesToFetch) {
			SearchRequest searchRequest = new SearchRequest();
			searchRequest.indices("lexcontentindex_authoring_tool").types("resource")
					.scroll(new TimeValue(scrollStateTime));
			SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
			searchSourceBuilder.query(QueryBuilders.boolQuery()
					.should(QueryBuilders.termQuery("contentType.keyword", contentTypeToFetch)).should(QueryBuilders
							.termsQuery("status.keyword", Arrays.asList("Draft", "InReview", "Reviewed"))));
			searchSourceBuilder.size(batchSize);
			searchRequest.source(searchSourceBuilder);
			SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
			List<Map<String, Object>> contentMaps = new ArrayList<>();
			while (response.getHits().getHits().length > 0) {
				for (SearchHit searchHit : response.getHits()) {
					Map<String, Object> source = searchHit.getSourceAsMap();
					contentMaps.add(source);
				}
				System.out.println("fetched " + batchSize + " nodes for creation");
				createContentFromAuthIndex(contentMaps);
				contentMaps.clear();
				String scrollId = response.getScrollId();
				SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
				scrollRequest.scroll(new TimeValue(scrollStateTime));
				response = client.scroll(scrollRequest, RequestOptions.DEFAULT);
			}
			createContentFromAuthIndex(contentMaps);
			break;
		}
	}

	private void createContentFromAuthIndex(List<Map<String, Object>> contentMaps) throws Exception {

		FileWriter authProcessWriter = new FileWriter("D:\\authProcessedContent.txt");

		String readUrl = "http://URL_DEFAULT:4011/action/content/read/";

		for (Map<String, Object> contentMap : contentMaps) {
			if (!contentMap.containsKey(LexConstants.IDENTIFIER)) {
				continue;
			}
			String identifier = contentMap.get(LexConstants.IDENTIFIER).toString();

			try {
				ResponseEntity<String> responseEntity = restTemplate
						.exchange(readUrl + identifier + "?rootOrg=" + rootOrg, HttpMethod.GET, null, String.class);
				if (responseEntity.getStatusCode().equals(HttpStatus.OK)) {
					System.out.println("node found creating image node" + contentMap.get(LexConstants.IDENTIFIER));
					if (!contentMap.containsKey(LexConstants.CONTENT_TYPE)) {
						continue;
					}
					if (contentMap.get(LexConstants.CONTENT_TYPE).equals(LexConstants.RESOURCE)) {
						createImageNodeResource(contentMap, authProcessWriter);
					} else {
						createImageNode(contentMap, authProcessWriter);
					}
					System.out.println("node updated " + identifier);
				}
			} catch (HttpStatusCodeException e) {
				if (e.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
					if (!contentMap.containsKey(LexConstants.CONTENT_TYPE)) {
						continue;
					}
					System.out.println("New content to be created " + mapper.writeValueAsString(contentMap));
					Session session = driver.session();
					Transaction transaction = session.beginTransaction();
					try {
						populateMetaForCreation(contentMap);
						graphService.createNodeV2(rootOrg, contentMap, transaction);
						transaction.commitAsync().toCompletableFuture().get();
						authProcessWriter.write("\"" + contentMap.get("identifier") + "\",");
						System.out.println("created node " + identifier);
					} catch (Exception e1) {
						e1.printStackTrace();
						transaction.rollbackAsync().toCompletableFuture().get();
						System.out.println("Failed creation of content" + contentMap.toString());
					} finally {
						session.close();
					}
				}
			}
		}
	}

	private void createHierarchyFromAuthTool() throws Exception {

		FileWriter fw = new FileWriter("D:\\invalidContent.txt");
		FileWriter createdWriter = new FileWriter("D:\\created.txt");
		FileWriter idsCreated = new FileWriter("D:\\created.txt");

		RestHighLevelClient client = restClient();

		SearchRequest searchRequest = new SearchRequest();
		searchRequest.indices("lexcontentindex_authoring_tool").types("resource")
				.scroll(new TimeValue(scrollStateTime));
		SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
		searchSourceBuilder.query(QueryBuilders.boolQuery()
				.should(QueryBuilders.termsQuery("status.keyword", Arrays.asList("Draft", "InReview", "Reviewed"))));
		searchSourceBuilder.size(batchSize);
		searchRequest.source(searchSourceBuilder);
		SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
		List<Map<String, Object>> contentMaps = new ArrayList<>();
		while (response.getHits().getHits().length > 0) {
			for (SearchHit searchHit : response.getHits()) {
				Map<String, Object> source = searchHit.getSourceAsMap();
				contentMaps.add(source);
			}
			System.out.println("fetched " + batchSize + " nodes for creation");
			createHierarchyUtil(contentMaps, fw, createdWriter, idsCreated);
			contentMaps.clear();
			String scrollId = response.getScrollId();
			SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
			scrollRequest.scroll(new TimeValue(scrollStateTime));
			response = client.scroll(scrollRequest, RequestOptions.DEFAULT);
		}
		createHierarchyUtil(contentMaps, fw, createdWriter, idsCreated);

	}

	private void createImageNode(Map<String, Object> contentMap, FileWriter authProcessWriter) throws IOException {

		if (!contentMap.containsKey(LexConstants.IDENTIFIER)) {
			return;
		}

		populateMetaForImageNodeCreation(contentMap);
		Map<String, Object> requestBody = new HashMap<>();
		Map<String, Object> hierarchy = new HashMap<>();
		Map<String, Object> nodesModified = new HashMap<>();

		requestBody.put("hierarchy", hierarchy);
		requestBody.put("nodesModified", nodesModified);

		Map<String, Object> idUpdateMap = new HashMap<>();
		idUpdateMap.put("root", true);
		Map<String, Object> metaDataMap = new HashMap<>(contentMap);
		idUpdateMap.put("metadata", metaDataMap);

		nodesModified.put(contentMap.get(LexConstants.IDENTIFIER).toString(), idUpdateMap);

		System.out.println("creating image node for " + mapper.writeValueAsString(contentMap));

		String url = "http://URL_DEFAULT:4011/action/content/hierarchy/update?rootOrg=" + rootOrg + "migration=yes";
		try {
			restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(requestBody), String.class);
			authProcessWriter.write("\"" + contentMap.get("identifier") + "\",");
		} catch (HttpStatusCodeException ex) {
			System.out.println(ex.getStatusCode() + " " + ex.getResponseBodyAsString());
		}
	}

	private void createImageNodeResource(Map<String, Object> contentMap, FileWriter authProcessWriter)
			throws IOException {

		if (!contentMap.containsKey(LexConstants.IDENTIFIER)) {
			return;
		}

		String identifier = contentMap.get(LexConstants.IDENTIFIER).toString();
		populateMetaForImageNodeCreation(contentMap);

		String url = "http://URL_DEFAULT:4011/action/content/update/";
		try {
			restTemplate.exchange(url + identifier + "?rootOrg=" + rootOrg, HttpMethod.POST,
					new HttpEntity<>(contentMap), String.class);
			authProcessWriter.write("\"" + contentMap.get("identifier") + "\",");
		} catch (HttpStatusCodeException ex) {
			System.out.println(ex.getStatusCode() + " " + ex.getResponseBodyAsString());
		}
	}

	public static void main(String args[]) throws Exception {

		Migration migration = new Migration();
		System.out.println("creating nodes");
		// migration.createNodes();
		System.out.println("created nodes. now creating hierarchy");
		// migration.createHierarchy();
		System.out.println("created hierachy");
		System.out.println("creating draft and image nodes");
		migration.createContentFromAuthIndex();
		System.out.println("created content from auth index");
		System.out.println("creating hierarchy from auth index");
		migration.createHierarchyFromAuthTool();
		System.out.println("created hierarchy from auth index");
		System.out.println("program finished");
	}

}
