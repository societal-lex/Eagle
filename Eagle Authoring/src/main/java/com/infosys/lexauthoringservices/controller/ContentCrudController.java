/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.infosys.lexauthoringservices.exception.BadRequestException;
import com.infosys.lexauthoringservices.model.Response;
import com.infosys.lexauthoringservices.service.ContentCrudService;
import com.infosys.lexauthoringservices.service.ValidationsService;
import com.infosys.lexauthoringservices.util.LexConstants;

@RestController
@RequestMapping("/action/content")
public class ContentCrudController {

	@Autowired
	ContentCrudService contentCrudService;

	@Autowired
	ValidationsService validationsService;

	@PostMapping("/create")
	public ResponseEntity<?> createContentNode(
			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,
			@RequestBody Map<String, Object> requestMap) throws Exception {

		Map<String, Object> responseMap = new HashMap<>();
		responseMap.put(LexConstants.IDENTIFIER, contentCrudService.createContentNode(rootOrg, org, requestMap));
		return new ResponseEntity<>(responseMap, HttpStatus.CREATED);
	}

	@PostMapping("/createMigration")
	public ResponseEntity<?> createContentNodeForMigration(
			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,
			@RequestBody Map<String, Object> requestMap) throws Exception {

		contentCrudService.createContentNodeForMigration(rootOrg, org, requestMap);
		return new ResponseEntity<>(HttpStatus.CREATED);
	}

	@GetMapping("/read/{identifier}")
	public ResponseEntity<?> getContentNode(@PathVariable("identifier") String identifier,
			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org) throws Exception {

		return new ResponseEntity<>(contentCrudService.getContentNode(rootOrg, identifier), HttpStatus.OK);
	}

	@PostMapping("/update/{identifier}")
	public ResponseEntity<?> updateContentNode(@PathVariable("identifier") String identifier,
			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,
			@RequestBody Map<String, Object> requestMap) throws Exception {

		contentCrudService.updateContentNode(rootOrg, org, identifier, requestMap);
		return new ResponseEntity<>(HttpStatus.OK);
	}

	@PostMapping("/delete/{identifier}")
	public ResponseEntity<?> contentDelete(@PathVariable("identifier") String identifier,
			@RequestParam(value = LexConstants.AUTHOR, required = true) String authorEmail,
			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,
			@RequestParam(value = LexConstants.USER_TYPE, defaultValue = "false") String userType) throws Exception {

		contentCrudService.contentDelete(identifier, authorEmail, rootOrg, userType);
		return new ResponseEntity<>(HttpStatus.OK);
	}

//	@PostMapping("/action/playlist/update/{identifier}")
//	public ResponseEntity<Response> updatePlaylistNode(@PathVariable("identifier") String identifier,
//			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
//			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys Ltd") String org,
//			@RequestBody Map<String,Object> requestMap) throws Exception{
//		contentCrudService.updatePlaylistNode(rootOrg,identifier,requestMap);
//		return new ResponseEntity<>(HttpStatus.OK);
//	}

	@GetMapping("/hierarchy/{identifier}")
	public ResponseEntity<?> getContentHierarchy(@PathVariable("identifier") String identifier,
			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org)
			throws BadRequestException, Exception {

		return new ResponseEntity<>(contentCrudService.getContentHierarchy(identifier, rootOrg, org), HttpStatus.OK);
	}

	@PostMapping("/hierarchy/fields/{identifier}")
	public ResponseEntity<?> getContentHierarchyFields(@PathVariable("identifier") String identifier,
			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,
			@RequestBody Map<String, Object> requestMap) throws Exception {

		return new ResponseEntity<>(contentCrudService.getContentHierarchyFields(identifier, rootOrg, org, requestMap),
				HttpStatus.OK);
	}

	@PostMapping("/hierarchy/update")
	public ResponseEntity<Response> updateContentHierarchy(
			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,
			@RequestParam(value = "migration", defaultValue = "no") String migration,
			@RequestBody Map<String, Object> requestMap) throws Exception {

		contentCrudService.updateContentHierarchy(rootOrg, org, requestMap, migration);
		return new ResponseEntity<>(HttpStatus.OK);
	}

//	@PostMapping("/action/content/publish/{identifier}")
//	public ResponseEntity<Response> publishContent(@PathVariable("identifier") String identifier,
//			@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
//			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,
//			@RequestParam(value = LexConstants.AUTHOR, defaultValue = "user1@demo.com") String creatorEmail)
//			throws Exception {
//
//		return new ResponseEntity<>(contentCrudService.publishContent(identifier, rootOrg, org, creatorEmail),
//				HttpStatus.OK);
//	}

	@PostMapping("/status/change/{identifier}")
	public ResponseEntity<Response> statusChange(@PathVariable("identifier") String identifier,
			@RequestBody Map<String, Object> requestBody) throws BadRequestException, Exception {

		return new ResponseEntity<>(contentCrudService.statusChange(identifier, requestBody), HttpStatus.OK);
	}

	@PostMapping("/action/content/external/publish/{identifier}")
	public ResponseEntity<Response> externalContentPublish(@PathVariable("identifier") String identifier,
			@RequestBody Map<String, Object> requestBody) throws Exception {

		return new ResponseEntity<>(contentCrudService.externalContentPublish(identifier, requestBody), HttpStatus.OK);
	}

	@PostMapping("/extend")
	public ResponseEntity<Response> extendContentExpiry(@RequestBody Map<String, Object> requestBody) throws Exception {
		return new ResponseEntity<>(contentCrudService.extendContentExpiry(requestBody), HttpStatus.OK);
	}

}
