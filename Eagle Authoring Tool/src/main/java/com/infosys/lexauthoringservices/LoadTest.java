/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Transaction;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.serviceimpl.GraphServiceImpl;
import com.infosys.lexauthoringservices.util.LexConstants;

public class LoadTest {

	private static final List<String> fieldsToParse = Arrays.asList(LexConstants.COMMENTS,
			LexConstants.CERTIFICATION_LIST, LexConstants.PLAYGROUND_RESOURCES, LexConstants.SOFTWARE_REQUIREMENTS,
			LexConstants.SYSTEM_REQUIREMENTS, LexConstants.REFERENCES, LexConstants.CREATOR_CONTACTS,
			LexConstants.CREATOR_DEATILS, LexConstants.PUBLISHER_DETAILS, LexConstants.PRE_CONTENTS,
			LexConstants.POST_CONTENTS, LexConstants.TAGS, LexConstants.CLIENTS, LexConstants.SKILLS,
			LexConstants.TRACK, LexConstants.K_ARTIFACTS, LexConstants.TRACK_CONTACT_DETAILS, LexConstants.ORG,
			LexConstants.SUBMITTER_DETAILS);

	private static void populateMetaForCreation(Map<String, Object> contentMap) {
		contentMap.remove("msArtifactDetails");
		contentMap.remove("comments");
		contentMap.remove("publisherDetails");
		contentMap.remove("children");
		contentMap.remove("collections");
		contentMap.remove("topics");
	}

	public static RestHighLevelClient restClient() {

		final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
		credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("UNAME", "PASSWORD"));

		RestClientBuilder builder = RestClient.builder(new HttpHost("URL_DEFAULT", Integer.parseInt("5903")))
				.setHttpClientConfigCallback(
						httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));

		RestHighLevelClient client = new RestHighLevelClient(builder);
		return client;
	}

	public static void main(String args[]) throws Exception {

		RestHighLevelClient client = restClient();
		SearchRequest searchRequest = new SearchRequest();
		searchRequest.indices("lexcontentindex").types("resource");
		SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
		searchSourceBuilder
				.query(QueryBuilders.boolQuery().must(QueryBuilders.termQuery("_id", "lex_29287429270366384000")));
		searchRequest.source(searchSourceBuilder);

		SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

		Driver driver = GraphDatabase.driver("bolt://URL_DEFAULT:7687");
		GraphServiceImpl graphService = new GraphServiceImpl();
		Session session = driver.session();
		Transaction transaction = session.beginTransaction();

		for (SearchHit searchHit : response.getHits()) {
			Map<String, Object> source = searchHit.getSourceAsMap();
			populateMetaForCreation(source);
			System.out.println(source.toString());
			graphService.createNodeV2("InfosysMigrationTest", source, transaction);
		}

		transaction.commitAsync().toCompletableFuture().get();
		System.out.println("done");

	}

//	public static void main(String args[])
//			throws JsonParseException, JsonMappingException, IOException, InterruptedException, ExecutionException {
//
//		LoadTest loadTest = new LoadTest();
//
//		CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
//			try {
//				loadTest.usertncTest();
//			} catch (Exception e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		});
//
//		CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
//			try {
//				loadTest.catalogTest();
//			} catch (Exception e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		});
//
//		CompletableFuture<Void> future3 = CompletableFuture.runAsync(() -> {
//			try {
//				loadTest.uuidToEmailTest();
//			} catch (Exception e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		});
//
//		CompletableFuture<Void> future4 = CompletableFuture.runAsync(() -> {
//			try {
//				loadTest.userRolesTest();
//			} catch (Exception e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//		});
//
//		future1.get();
//		future2.get();
//		future3.get();
//		future4.get();
//	}

	private void userRolesTest()
			throws JsonParseException, JsonMappingException, IOException, InterruptedException, ExecutionException {
		RestTemplate restTemplate = new RestTemplate();

		HttpHeaders headers = new HttpHeaders();
		headers.set("Client_Id", "1004");
		headers.set("Api_Key", "KEYVALUE");

		List<String> userEmails = Arrays.asList("EMAIL","EMAIL");

		String url = "http://URL_DEFAULT:3010/v1/hr/aspiredroles?userEmail=";

		List<CompletableFuture<Void>> futures = new ArrayList<>();

		for (String userEmail : userEmails) {

			CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
				ResponseEntity<String> responseEntity = restTemplate.exchange(url + userEmail, HttpMethod.GET,
						new HttpEntity<Object>(headers), String.class);
				System.out.println(responseEntity.getBody());
			});

			futures.add(future);
		}

		for (CompletableFuture<Void> future : futures) {
			future.get();
		}
	}

	@SuppressWarnings("unchecked")
	private void usertncTest() throws JsonParseException, JsonMappingException, IOException {
		RestTemplate restTemplate = new RestTemplate();

		HttpHeaders headers = new HttpHeaders();
		headers.set("Client_Id", "1002");
		headers.set("Api_Key", "VALUE");

		String url = "http://URL_DEFAULT:3010/v1/la/usertnc?pageSize=100";
		String recordLink = "";

		while (true) {

			ResponseEntity<String> responseEntity = restTemplate.exchange(url + recordLink, HttpMethod.GET,
					new HttpEntity<Object>(headers), String.class);

			Map<String, Object> responseMap = new ObjectMapper().readValue(responseEntity.getBody(),
					new TypeReference<Map<String, Object>>() {
					});

			responseMap = (Map<String, Object>) responseMap.get("result");
			System.out.println(responseMap.toString());

			String nextRecord = responseMap.get("nextRecord").toString();

			if (nextRecord.equals("-1")) {
				break;
			}
			recordLink = "&recordLink=" + nextRecord;
		}
	}

	@SuppressWarnings("unchecked")
	private void uuidToEmailTest() throws JsonParseException, JsonMappingException, IOException {
		RestTemplate restTemplate = new RestTemplate();

		HttpHeaders headers = new HttpHeaders();
		headers.set("Client_Id", "1002");
		headers.set("Api_Key", "VALUE");

		String url = "http://URL_DEFAULT:3010/v1/lex/emailFromUserId?pageSize=100";
		String recordLink = "";

		while (true) {

			ResponseEntity<String> responseEntity = restTemplate.exchange(url + recordLink, HttpMethod.GET,
					new HttpEntity<Object>(headers), String.class);

			Map<String, Object> responseMap = new ObjectMapper().readValue(responseEntity.getBody(),
					new TypeReference<Map<String, Object>>() {
					});

			responseMap = (Map<String, Object>) responseMap.get("result");
			System.out.println(responseMap.toString());

			String nextRecord = responseMap.get("nextRecord").toString();

			if (nextRecord.equals("-1")) {
				break;
			}
			recordLink = "&recordLink=" + nextRecord;
		}
	}

	@SuppressWarnings("unchecked")
	private void catalogTest() throws JsonParseException, JsonMappingException, IOException {
		RestTemplate restTemplate = new RestTemplate();

		HttpHeaders headers = new HttpHeaders();
		headers.set("Client_Id", "1002");
		headers.set("Api_Key", "VALUE");

		String url = "http://URL_DEFAULT:3010/v1/la/catalog?pageSize=1000";

		String recordLink = "";

		while (true) {

			ResponseEntity<String> responseEntity = restTemplate.exchange(url + recordLink, HttpMethod.GET,
					new HttpEntity<Object>(headers), String.class);

			Map<String, Object> responseMap = new ObjectMapper().readValue(responseEntity.getBody(),
					new TypeReference<Map<String, Object>>() {
					});

			responseMap = (Map<String, Object>) responseMap.get("result");
			System.out.println(responseMap.toString());

			String nextRecord = responseMap.get("nextRecord").toString();

			if (nextRecord.equals("-1")) {
				break;
			}
			recordLink = "&recordLink=" + nextRecord;
		}
	}
}
