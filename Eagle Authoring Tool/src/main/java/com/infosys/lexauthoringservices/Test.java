/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.TransactionWork;
import org.neo4j.driver.v1.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.exception.BadRequestException;
import com.infosys.lexauthoringservices.service.ContentCrudService;
import com.infosys.lexauthoringservices.serviceimpl.ContentCrudServiceImpl;
import com.infosys.lexauthoringservices.util.LexConstants;
import com.kenai.jffi.CallContextCache;

public class Test {
	static RestTemplate restTemplate = new RestTemplate();
	
	static ContentCrudService contentCrudService = new ContentCrudServiceImpl();

	public static void main(String[] args) throws BadRequestException, Exception {
		getToBeDeletedContents("lex_auth_01281547607537254410", "Test");
		Map<String,Object> hMap = contentCrudService.getContentHierarchy("lex_auth_0128224550625525766", "Infosys", "Infosys Ltd");
		System.out.println(hMap);
		calcChildTitleDesc(hMap);
		System.out.println("------------------------");
		System.out.println(hMap);
//		System.out.println("Hi starting");
//		Map<String, Object> sendMap = new HashMap<>();
//		sendMap.put("itemId", "lex_1");
//		sendMap.put("filePath", "");
//		sendMap.put("itemMimeType", LexConstants.MIME_TYPE_PDF);
//		sendMap.put("textExtracted", "some sample text");
////		ResponseEntity<String> response = restTemplate.postForLocation("http://URL_DEFAULT:3014/api/v1/topic/storingTextualContent",sendMap);
//		// restTemplate.postForEntity("http://URL_DEFAULT:3014/api/v1/topic/storingTextualContent",sendMap,
//		// responseType);
//		System.out.println("Sent");
//		try {
//			ResponseEntity<String> responseEntity = restTemplate.exchange(
//					"http://URL_DEFAULT:3014/api/v1/topic/storingTextualContent", HttpMethod.POST,
//					new HttpEntity<Object>(sendMap), String.class);
//			System.out.println(responseEntity.getStatusCode());
//			System.out.println(responseEntity.getBody());
//		} catch (HttpServerErrorException e) {
//			System.out.println(e.getStatusCode());
//			System.out.println(e.getResponseBodyAsString());
//		}

	}
	
	@SuppressWarnings("unchecked")
	private static void calcChildTitleDesc(Map<String, Object> contentHierarchy) {

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
					if (!childTitle.containsKey(child.get(LexConstants.IDENTIFIER).toString())) {
						childTitleExists = false;
						stack.push(child);
					}
					if (!childDesc.containsKey(child.get(LexConstants.IDENTIFIER).toString())) {
						childDescExists = false;
						stack.push(child);
					}
				}
				if (childTitleExists) {
					stack.pop();
					List<String> parentTitle = new ArrayList<>();
					for (Map<String, Object> child : children) {
						parentTitle.add((String) childTitle.get(child.get(LexConstants.IDENTIFIER).toString()));
					}
					parent.put(LexConstants.CHILD_TITLE, parentTitle);
					childTitle.put(parent.get(LexConstants.IDENTIFIER).toString(), parentTitle);
				}
				
				if (childDescExists) {
					stack.pop();
					List<String> parentDesc = new ArrayList<>();
					for (Map<String, Object> child : children) {
						parentDesc.add((String) childDesc.get(child.get(LexConstants.IDENTIFIER).toString()));
					}
					parent.put(LexConstants.CHILD_DESC, parentDesc);
					childDesc.put(parent.get(LexConstants.IDENTIFIER).toString(), parentDesc);
				}
			}
		}
	}

	
	private static List<String> getToBeDeletedContents(String identifier,String rootOrg)
	{
		Driver neo4jDriver = GraphDatabase.driver("bolt://URL_DEFAULT:7687");
		Session session = neo4jDriver.session();
		List<String> tbdContent = session.readTransaction(new TransactionWork<List<String>>() {

			@Override
			public List<String> execute(Transaction tx) {
				String query = "match(n{identifier:'"+ identifier +"'}) where n:Shared or n:" + rootOrg + " with n optional match(n)-[r:Has_Sub_Content*]->(s) where s:Shared or s:" + rootOrg + " and n.status='toBeDeleted' and s.status='toBeDeleted' return s.identifier";
				System.out.println("Runnning query");
				StatementResult statementResult= tx.run(query);
				List<Record> records = statementResult.list();
				List<String> tbdContents = new ArrayList<>();
				for(Record rec: records) {
					System.out.println(rec.get("s.identifier"));
					String id = rec.get("s.identifier").toString();
					id = id.replace("\"", "");
					tbdContents.add(id);
				}
				return tbdContents;
			}
		});
		tbdContent.add(identifier);
		System.out.println("----");
		System.out.println(tbdContent);
		return tbdContent;
		
	}
}
