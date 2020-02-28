/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.serviceimpl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.exception.ApplicationLogicError;
import com.infosys.lexauthoringservices.service.GeneratePlagiarismService;
import com.infosys.lexauthoringservices.util.LexConstants;

@Service
public class GeneratePlagiarismServiceImpl implements GeneratePlagiarismService {

	@Autowired
	RestTemplate restTemplate;

	@Autowired
	ContentCrudServiceImpl contentCrudServiceImpl;

	Path des = null;

	@Override
	public File generatePlagiarismReport(String identifier, String domain, String rootOrg,String org) {
		System.out.println("Hi from serviceImpl line 1");
		File txtFile = null;
		File zipFolder = null;

		try {
			StringBuffer writeText = new StringBuffer();

			if (Files.notExists(Paths.get("Plagiarism"))) {
				new File("Plagiarism").mkdir();
			}
			generatePlagiarism(identifier, writeText, true, identifier, domain, rootOrg,org);
			System.out.println("back after writeBuffer creation " + writeText);
			txtFile = new File("Plagiarism/" + identifier + "/" + identifier + ".html");
			new File("Plagiarism/" + identifier).mkdir();
			BufferedWriter writer = new BufferedWriter(new FileWriter(txtFile));
			writer.write(writeText.toString());
			writer.flush();
			writer.close();
			String src = "Plagiarism/" + identifier;
			String dest = "Plagiarism/" + identifier + ".zip";
			zipFolder = new File(dest);
			zipFolder(Paths.get(src), Paths.get(dest));

			if (!zipFolder.exists()) {
				System.out.println("FILE NOT FOUND");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return zipFolder;
	}
	
	

	@SuppressWarnings("unchecked")
	private void generatePlagiarism(String identifier, StringBuffer writeText, boolean firstRecur,
			String parentIdentifier, String domain, String rootOrg,String org) {
		Map<String, Object> contentMeta = new HashMap<>();
		if (firstRecur) {
			if (!new File("Plagiarism/" + identifier).mkdir()) {
				try {
					identifier = identifier.trim();
					des = Paths.get("Plagiarism/" + identifier);
				} catch (Exception e) {
					System.out.println("Error: Cannot create the URI dest path");
					e.printStackTrace();
					throw new ApplicationLogicError("Error: Cannot create the URI dest path");

				}
			}
			firstRecur = false;
		}
		try {
			contentMeta = (Map<String, Object>) contentCrudServiceImpl.getContentHierarchy(identifier,rootOrg,org);
//			contentMeta = (Map<String, Object>) contentMeta.get(LexConstants.CONTENT);
//			System.out.println(contentMeta);
		} catch (Exception e) {
			e.printStackTrace();
		}
		String contentType = contentMeta.get(LexConstants.CONTENT_TYPE).toString();
		writeText.append("<b>" + identifier + ":" + contentType + "<br><br>" + "\r\n" + "\r\n" + "</b>");
		hierarchyIterator(contentMeta, writeText, domain);

	}

	@SuppressWarnings("unchecked")
	private void hierarchyIterator(Map<String, Object> contentMeta, StringBuffer writeText, String domain) {
		System.out.println(contentMeta);
		Queue<Map<String, Object>> parentObjs = new LinkedList<>();
		parentObjs.add(contentMeta);
		while (!parentObjs.isEmpty()) {
			Map<String, Object> parent = parentObjs.poll();
			System.out.println(parent);
			System.out.println(parent.get(LexConstants.CONTENT_TYPE));
			System.out.println(LexConstants.RESOURCE);
			if (parent.get(LexConstants.CONTENT_TYPE).equals(LexConstants.RESOURCE)) {
				System.out.println("found resource");
				resourceHandler(parent, writeText, domain);
				break;
			}
			List<Map<String, Object>> childrenList = (List<Map<String, Object>>) parent.get(LexConstants.CHILDREN);
			for (Map<String, Object> child : childrenList) {
				String childContentType = child.get(LexConstants.CONTENT_TYPE).toString();
				if (!childContentType.equals(LexConstants.RESOURCE)) {
					writeText.append("<b>" + child.get(LexConstants.IDENTIFIER) + " : " + childContentType + "<br><br>"
							+ "\r\n" + "\r\n" + "</b>");
				} else {
					resourceHandler(child, writeText, domain);
				}
			}
			parentObjs.addAll(childrenList);
		}
	}
	
	private void zipFolder(Path sourceFolderPath, Path zipPath) throws Exception {
		ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()));
		Files.walkFileTree(sourceFolderPath, new SimpleFileVisitor<Path>() {
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				zos.putNextEntry(new ZipEntry(sourceFolderPath.relativize(file).toString()));
				Files.copy(file, zos);
				zos.closeEntry();
				return FileVisitResult.CONTINUE;
			}
		});
		zos.close();
	}


	private void resourceHandler(Map<String, Object> resourceMeta, StringBuffer writeText, String domain) {
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
			} else if (mimeType.equals(LexConstants.MIME_TYPE_QUIZ)) {
				System.out.println("before quiz function");
				generateQuizJson(resourceMeta, writeText, domain);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_PDF)) {
				generatePDF(resourceMeta, writeText, domain);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_HANDSONQUIZ)) {
				generateIntegratedHandsOn(resourceMeta, writeText, domain);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_DNDQUIZ)) {
				generateDragDrop(resourceMeta, writeText, domain);
			} else if (mimeType.equals(LexConstants.MIME_TYPE_HTMLQUIZ)) {
				generateHTMLQuiz(resourceMeta,writeText,domain);
			}
		}
	}

//	@SuppressWarnings("unchecked")
//	private void generateForWebHtml(Map<String,Object> contentMeta, StringBuffer writeText, String domain) {
//		try {
//			ObjectMapper mapper = new ObjectMapper();
//			String artUrl = getArtifactUrl(contentMeta.getArtifactUrl());
//			String identifier = contentMeta.getIdentifier();
//			URL artifactUrl = new URL(artUrl);
//			List<Map<String, Object>> manifestJson = (List<Map<String, Object>>) mapper.readValue(artifactUrl,
//					List.class);
//			for (Map<String, Object> mObj : manifestJson) {
//				String fName = mObj.get("URL").toString();
//				String fileUrl = "";
//				String parameter = "/web-hosted%2Fauth/" + identifier + "/" + fName;
//				UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(LexProjectUtil.getWebhostProxy() + "/").path(parameter);
//				UriComponents components = builder.build(true);
//				URI uri = components.toUri();
//				fileUrl = uri.toString();
//				URL getUrl = new URL(fileUrl);
//				System.out.println(getUrl);
//				BufferedReader reader = new BufferedReader(new InputStreamReader(getUrl.openStream()));
//				String line;
//				String viewerUrl = domain;
//				viewerUrl = viewerUrl + "/" + identifier;
//				writeText.append("Viewer URL : " + "<a href = \"" + viewerUrl + "\" target=\"_blank\"" + ">"
//						+ "Click here" + "</a>" + "\r\n");
//				while ((line = reader.readLine()) != null) {
//					writeText.append(line);
//				}
//				writeText.append("\r\n");
//				reader.close();
//				// System.out.println("A run has been completed");
//			}
//		} catch (Exception e) {
//			System.out.println("Error inside the funcion:'File Not Found(html,web-module)'");
//			e.printStackTrace();
//		}
//
//	}

	
	
	@SuppressWarnings("unchecked")
	private void generateHTMLQuiz(Map<String, Object> resourceMeta, StringBuffer writeText, String domain) {
		ObjectMapper mapper = new ObjectMapper();
		URL artifactUrl = null;
		try {
			artifactUrl = new URL((String) resourceMeta.get(LexConstants.ARTIFACT_URL));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		String viewerUrl = domain + "/" + resourceMeta.get(LexConstants.IDENTIFIER);
		writeText.append("Viewer URL : " + "<a href = \"" + viewerUrl + "\" target=\"_blank\"" + ">" + "Click here"
				+ "</a>" + "\r\n");
		Map<String, Object> htmlJson = null;
		try {
			htmlJson = mapper.readValue(artifactUrl, Map.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		writeText.append("<br><div>" + htmlJson.get("question"));
		writeText.append("<br><div>" + htmlJson.get("html"));

		
	}

	@SuppressWarnings("unchecked")
	private void generateDragDrop(Map<String, Object> resourceMeta, StringBuffer writeText, String domain) {
		ObjectMapper mapper = new ObjectMapper();
		URL artifactUrl = null;
		try {
			artifactUrl = new URL((String) resourceMeta.get(LexConstants.ARTIFACT_URL));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		String viewerUrl = domain + "/" + resourceMeta.get(LexConstants.IDENTIFIER);
		writeText.append("Viewer URL : " + "<a href = \"" + viewerUrl + "\" target=\"_blank\"" + ">" + "Click here"
				+ "</a>" + "\r\n");
		Map<String, Object> dndJson = null;
		try {
			dndJson = (Map<String, Object>) mapper.readValue(artifactUrl, Map.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		Map<String, Object> dndQuestions = (Map<String, Object>) dndJson.get("dndQuestions");
		Map<String, Object> options = (Map<String, Object>) dndQuestions.get("options");
		writeText.append(dndQuestions.get("question"));
		writeText.append("<br><div>");
		List<Map<String, Object>> answerOptions = (List<Map<String, Object>>) options.get("answerOptions");
		List<Map<String, Object>> additionalOptions = (List<Map<String, Object>>) options.get("additionalOptions");
		writeText.append("Answer Options: ");
		writeText.append("<ul>");
		for (Map<String, Object> ansOptObj : answerOptions) {
			writeText.append("<li>" + ansOptObj.get("text") + "</li>");
		}
		writeText.append("</ul>");
		writeText.append("<br>");
		writeText.append("Additional Options: ");
		writeText.append("<ul>");
		for (Map<String, Object> addOptObj : additionalOptions) {
			writeText.append("<li>" + addOptObj.get("text") + "</li>");
		}
		writeText.append("<ul>");

	}

	@SuppressWarnings("unchecked")
	private void generateIntegratedHandsOn(Map<String, Object> resourceMeta, StringBuffer writeText, String domain) {
		ObjectMapper mapper = new ObjectMapper();
		URL artifactUrl = null;
		try {
			artifactUrl = new URL((String) resourceMeta.get(LexConstants.ARTIFACT_URL));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		String viewerUrl = domain + "/" + resourceMeta.get(LexConstants.IDENTIFIER);
		writeText.append("Viewer URL : " + "<a href = \"" + viewerUrl + "\" target=\"_blank\"" + ">" + "Click here"
				+ "</a>" + "\r\n");
		Map<String, Object> integratedJson = null;
		try {
			integratedJson = (Map<String, Object>) mapper.readValue(artifactUrl, Map.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		writeText.append("<br><div>" + integratedJson.get("problemStatement"));
	}

	private void generatePDF(Map<String, Object> resourceMeta, StringBuffer writeText, String domain) {
		try {
			String identifier = (String) resourceMeta.get(LexConstants.IDENTIFIER);
			String artifactUrl = (String) resourceMeta.get(LexConstants.ARTIFACT_URL);
			String pdfFileName = artifactUrl.substring(artifactUrl.lastIndexOf("/"));
			String filePath = "content/TestAuth/" + identifier + pdfFileName;
			// TODO
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@SuppressWarnings("unchecked")
	private void generateQuizJson(Map<String, Object> resourceMeta, StringBuffer writeText, String domain) {
		ObjectMapper mapper = new ObjectMapper();
		URL artifactUrl = null;
		try {
			artifactUrl = new URL((String) resourceMeta.get(LexConstants.ARTIFACT_URL));

		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		String viewerUrl = domain + "/" + resourceMeta.get(LexConstants.IDENTIFIER);
		writeText.append("Viewer URL : " + "<a href = \"" + viewerUrl + "\" target=\"_blank\"" + ">" + "Click here"+ "</a>" + "\r\n");
		System.out.println(writeText);
		Map<String, Object> quizJson = new HashMap<>();
		try {
			quizJson = (Map<String, Object>) mapper.readValue(artifactUrl, Map.class);
		} catch (IOException e) {
			e.printStackTrace();
		}
		try{
			List<Map<String, Object>> questions = (List<Map<String, Object>>) quizJson.get("questions");
			for (Map<String, Object> question : questions) {
				writeText.append("<br><div>" + question.get("question"));
				List<Map<String, Object>> options = (List<Map<String, Object>>) question.get("options");
				writeText.append("<ul>");
				for (Map<String, Object> optionObj : options) {
					writeText.append("<li>" + optionObj.get("text") + "</l>");
				}
				writeText.append("</ul>");
				writeText.append("<br>");
				for (Map<String, Object> optionObj : options) {
					if (optionObj.get("hint") != null) {
						writeText.append("<li> Hint:" + optionObj.get("hint") + "</l>");
					}
				}
			}
			writeText.append("</div><br><br>");
		}catch (Exception e) {
			e.printStackTrace();
		}
	}
}
