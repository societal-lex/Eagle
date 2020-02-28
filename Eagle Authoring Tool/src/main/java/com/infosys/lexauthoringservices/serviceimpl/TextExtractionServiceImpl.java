/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.serviceimpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.model.Response;
import com.infosys.lexauthoringservices.service.ContentCrudService;
import com.infosys.lexauthoringservices.service.TextExtractionService;
import com.infosys.lexauthoringservices.util.LexConstants;
import com.infosys.lexauthoringservices.util.LexServerProperties;

public class TextExtractionServiceImpl implements TextExtractionService{
	
	@Autowired
	ContentCrudService contentCrudService;
	
	@Autowired
	RestTemplate restTemplate;
	
	@Autowired
	LexServerProperties lexServerProps;
	
	@Override
	public Response resourceTextExtraction(String rootOrg, String org, Map<String, Object> reqMap) throws Exception {
		Response response = new Response();
		String identifier = (String) reqMap.get(LexConstants.IDENTIFIER);
		Map<String,Object> contentMeta = contentCrudService.getContentHierarchy(identifier, rootOrg, org);
		hierarchyIterator(contentMeta);
		response.put("Message", "Successful");
		//TODO call 2nd shaan API to send top-level-id(identifier)
		return response;
	}
	
	//TODO most likely will not be used..
	@Override
	public Response hierarchialExtraction(String rootOrg, String org, Map<String, Object> requestMap) throws Exception {
		Response response = new Response();
		String identifier = (String) requestMap.get(LexConstants.IDENTIFIER);
		Map<String,Object> contentMeta = contentCrudService.getContentHierarchy(identifier, rootOrg, org);
		createTextBlock(contentMeta);
		System.out.println(contentMeta);
		//TODO call shaan API for hierarchial topics
		response.put("Message", "Successful");
		return response;
	}
	
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void createTextBlock(Map<String, Object> contentMeta) {
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(contentMeta);
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			List<Map<String, Object>> childrenList = (ArrayList) parent.get(LexConstants.CHILDREN);
			for(Map<String, Object> child:childrenList) {
				List<String> textBlock = new ArrayList<>();
				String name = (String) child.getOrDefault(LexConstants.NAME, " ");
				String desc = (String) child.getOrDefault(LexConstants.DESC, " ");
				String lo = (String) child.getOrDefault(LexConstants.LEARNING_OBJECTIVE, " ");
				List<String> keywords = (List<String>) child.get(LexConstants.KEYWORDS);
				String strKeywords = String.join(",", keywords);
				textBlock.add(name);
				textBlock.add(desc);
				textBlock.add(lo);
				textBlock.add(strKeywords);
				child.put("textBlock", textBlock);
			}
			parent.put(LexConstants.CHILDREN, childrenList);
			parentObjs.addAll(childrenList);
		}
	}

	//PRIVATE METHODS
	@SuppressWarnings("unchecked")
	private void hierarchyIterator(Map<String, Object> contentMeta) throws Exception {
		System.out.println(contentMeta);
		StringBuffer writeText = new StringBuffer();
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(contentMeta);
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			if (parent.get(LexConstants.CONTENT_TYPE).equals(LexConstants.RESOURCE)) {
				resourceHandler(parent);
				break;
			}
			List<Map<String, Object>> childrenList = (List<Map<String, Object>>) parent.get(LexConstants.CHILDREN);
			for (Map<String, Object> child : childrenList) {
				String childContentType = child.get(LexConstants.CONTENT_TYPE).toString();
				if (!childContentType.equals(LexConstants.RESOURCE)) {
					writeText.append("<b>" + child.get(LexConstants.IDENTIFIER) + " : " + childContentType + "<br><br>"
							+ "\r\n" + "\r\n" + "</b>");
				} else {
					resourceHandler(child);
				}
			}
			parentObjs.addAll(childrenList);
		}
	}
	
	
	private void resourceHandler(Map<String, Object> resourceMeta) throws Exception {
		System.out.println("inside resource handler");
		boolean isExternal = false;
		try {
			isExternal = (boolean) resourceMeta.get(LexConstants.ISEXTERNAL);
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (isExternal == false) {
			String mimeType = (String) resourceMeta.get(LexConstants.MIME_TYPE);
			if (mimeType.equals(LexConstants.MIME_TYPE_WEB) || mimeType.equals(LexConstants.MIME_TYPE_HTML)) {
				generateForWebHtml(resourceMeta);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_QUIZ)) {
				generateQuizJson(resourceMeta);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_PDF)) {
				generatePdf(resourceMeta);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_HANDSONQUIZ)) {
				generateIntegratedHandsOn(resourceMeta);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_DNDQUIZ)) {
				generateDragDrop(resourceMeta);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_HTMLQUIZ)) {
				generateHTMLQuiz(resourceMeta);
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private void generateQuizJson(Map<String, Object> resourceMeta) {
		ObjectMapper mapper = new ObjectMapper();
		StringBuffer writeText = new StringBuffer();
		URL artifactUrl = null;
		try {
			artifactUrl = new URL((String) resourceMeta.get(LexConstants.ARTIFACT_URL));

		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		Map<String, Object> quizJson = new HashMap<>();
		try {
			quizJson = (Map<String, Object>) mapper.readValue(artifactUrl, Map.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		try{
			List<Map<String, Object>> questions = (List<Map<String, Object>>) quizJson.get("questions");
			for (Map<String, Object> question : questions) {
				writeText.append(question.get("question") + " ");
				List<Map<String, Object>> options = (List<Map<String, Object>>) question.get("options");
				for (Map<String, Object> optionObj : options) {
					writeText.append(optionObj.get("text") + " ");
				}
				for (Map<String, Object> optionObj : options) {
					if (optionObj.get("hint") != null) {
						writeText.append(optionObj.get("hint") + " ");
					}
				}
			}
		System.out.println(writeText);
		Map<String,Object> sendMap = new HashMap<>();
		sendMap.put("itemId", resourceMeta.get(LexConstants.IDENTIFIER));
		sendMap.put("filePath", "");
		sendMap.put("itemMimeType", LexConstants.MIME_TYPE_QUIZ);
		sendMap.put("textExtracted", writeText);
		//TODO writeText to shaan API
		try {
			ResponseEntity<String> responseEntity = restTemplate.exchange(
					lexServerProps.getTopicServiceUrl(), HttpMethod.POST,
					new HttpEntity<Object>(sendMap), String.class);
			System.out.println(responseEntity.getStatusCode());
			System.out.println(responseEntity.getBody());
		} catch (HttpServerErrorException e) {
			System.out.println(e.getStatusCode());
			System.out.println(e.getResponseBodyAsString());
		}
		}catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings("unchecked")
	private void generateIntegratedHandsOn(Map<String, Object> resourceMeta) {
		StringBuffer writeText = new StringBuffer();
		ObjectMapper mapper = new ObjectMapper();
		URL artifactUrl = null;
		try {
			artifactUrl = new URL((String) resourceMeta.get(LexConstants.ARTIFACT_URL));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		Map<String, Object> integratedJson = null;
		try {
			integratedJson = (Map<String, Object>) mapper.readValue(artifactUrl, Map.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		writeText.append(integratedJson.get("problemStatement") + " ");
		//TODO shaan API
		Map<String,Object> sendMap = new HashMap<>();
		sendMap.put("itemId", resourceMeta.get(LexConstants.IDENTIFIER));
		sendMap.put("filePath", "");
		sendMap.put("itemMimeType",LexConstants.MIME_TYPE_HANDSONQUIZ);
		sendMap.put("textExtracted", writeText);
		try {
			ResponseEntity<String> responseEntity = restTemplate.exchange(
					lexServerProps.getTopicServiceUrl(), HttpMethod.POST,
					new HttpEntity<Object>(sendMap), String.class);
			System.out.println(responseEntity.getStatusCode());
			System.out.println(responseEntity.getBody());
		} catch (HttpServerErrorException e) {
			System.out.println(e.getStatusCode());
			System.out.println(e.getResponseBodyAsString());
		}
	}
	
	@SuppressWarnings("unchecked")
	private void generateDragDrop(Map<String, Object> resourceMeta) {
		StringBuffer writeText = new StringBuffer();
		ObjectMapper mapper = new ObjectMapper();
		URL artifactUrl = null;
		try {
			artifactUrl = new URL((String) resourceMeta.get(LexConstants.ARTIFACT_URL));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		Map<String, Object> dndJson = null;
		try {
			dndJson = (Map<String, Object>) mapper.readValue(artifactUrl, Map.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		Map<String, Object> dndQuestions = (Map<String, Object>) dndJson.get("dndQuestions");
		Map<String, Object> options = (Map<String, Object>) dndQuestions.get("options");
		writeText.append(dndQuestions.get("question") + " ");
		List<Map<String, Object>> answerOptions = (List<Map<String, Object>>) options.get("answerOptions");
		List<Map<String, Object>> additionalOptions = (List<Map<String, Object>>) options.get("additionalOptions");
		writeText.append("Answer Options: ");
		for (Map<String, Object> ansOptObj : answerOptions) {
			writeText.append(ansOptObj.get("text") + " ");
		}
		for (Map<String, Object> addOptObj : additionalOptions) {
			writeText.append(addOptObj.get("text") + " ");
		}
		//TODO shaan API
		Map<String,Object> sendMap = new HashMap<>();
		sendMap.put("itemId", resourceMeta.get(LexConstants.IDENTIFIER));
		sendMap.put("filePath", "");
		sendMap.put("itemMimeType", LexConstants.MIME_TYPE_DNDQUIZ);
		sendMap.put("textExtracted", writeText);
		try {
			ResponseEntity<String> responseEntity = restTemplate.exchange(
					lexServerProps.getTopicServiceUrl(), HttpMethod.POST,
					new HttpEntity<Object>(sendMap), String.class);
			System.out.println(responseEntity.getStatusCode());
			System.out.println(responseEntity.getBody());
		} catch (HttpServerErrorException e) {
			System.out.println(e.getStatusCode());
			System.out.println(e.getResponseBodyAsString());
		}
	}
	
	@SuppressWarnings("unchecked")
	private void generateHTMLQuiz(Map<String, Object> resourceMeta) {
		ObjectMapper mapper = new ObjectMapper();
		StringBuffer writeText = new StringBuffer();
		URL artifactUrl = null;
		try {
			artifactUrl = new URL((String) resourceMeta.get(LexConstants.ARTIFACT_URL));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		Map<String, Object> htmlJson = null;
		try {
			htmlJson = mapper.readValue(artifactUrl, Map.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		writeText.append(htmlJson.get("question") + " ");
		writeText.append(htmlJson.get("html") + " ");
		//TODO shaan API
		Map<String,Object> sendMap = new HashMap<>();
		sendMap.put("itemId", resourceMeta.get(LexConstants.IDENTIFIER));
		sendMap.put("filePath", "");
		sendMap.put("itemMimeType", LexConstants.MIME_TYPE_HTMLQUIZ);
		sendMap.put("textExtracted", writeText);
		try {
			ResponseEntity<String> responseEntity = restTemplate.exchange(
					lexServerProps.getTopicServiceUrl(), HttpMethod.POST,
					new HttpEntity<Object>(sendMap), String.class);
			System.out.println(responseEntity.getStatusCode());
			System.out.println(responseEntity.getBody());
		} catch (HttpServerErrorException e) {
			System.out.println(e.getStatusCode());
			System.out.println(e.getResponseBodyAsString());
		}
		
	}
	
	@SuppressWarnings("unchecked")
	private void generateForWebHtml(Map<String,Object> contentMeta) throws Exception {
		try {
			ObjectMapper mapper = new ObjectMapper();
			StringBuffer writeText = new StringBuffer();
			String artUrl = (String) contentMeta.get(LexConstants.ARTIFACT_URL);
			String identifier = (String) contentMeta.get(LexConstants.IDENTIFIER);
			URL artifactUrl = new URL(artUrl);
			List<Map<String, Object>> manifestJson = (List<Map<String, Object>>) mapper.readValue(artifactUrl,List.class);
			for (Map<String, Object> mObj : manifestJson) {
//				String fName = mObj.get("URL").toString();
//				String fileUrl = "";
//				String parameter = "/web-hosted%2Fauth/" + identifier + "/" + fName;
//				//TODO need s3 file folder structure help
//				UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(LexProjectUtil.getWebhostProxy() + "/").path(parameter);
//				UriComponents components = builder.build(true);
//				URI uri = components.toUri();
//				fileUrl = uri.toString();
//				URL getUrl = new URL(fileUrl);
//				System.out.println(getUrl);
//				BufferedReader reader = new BufferedReader(new InputStreamReader(getUrl.openStream()));
//				String line;
//				while ((line = reader.readLine()) != null) {
//					writeText.append(line);
//				}
//				writeText.append("\r\n");
//				reader.close();
				// System.out.println("A run has been completed");
			}
		} catch (Exception e) {
			System.out.println("Error inside the funcion:'File Not Found(html,web-module)'");
			e.printStackTrace();
			throw new Exception(e);
		}

	}
	
	private void generatePdf(Map<String,Object> resourceMeta) {
		try {
			String identifier = (String) resourceMeta.get(LexConstants.IDENTIFIER);
			String artUrl = (String) resourceMeta.get(LexConstants.ARTIFACT_URL);
			StringBuffer writeText = new StringBuffer();
			Map<String,Object> sendMap = new HashMap<>();
			sendMap.put("itemId", resourceMeta.get(LexConstants.IDENTIFIER));
			sendMap.put("filePath", artUrl);
			sendMap.put("itemMimeType", LexConstants.MIME_TYPE_PDF);
			sendMap.put("textExtracted", writeText);
			try {
				ResponseEntity<String> responseEntity = restTemplate.exchange(
						lexServerProps.getTopicServiceUrl(), HttpMethod.POST,
						new HttpEntity<Object>(sendMap), String.class);
				System.out.println(responseEntity.getStatusCode());
				System.out.println(responseEntity.getBody());
			} catch (HttpServerErrorException e) {
				System.out.println(e.getStatusCode());
				System.out.println(e.getResponseBodyAsString());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}



	
}
