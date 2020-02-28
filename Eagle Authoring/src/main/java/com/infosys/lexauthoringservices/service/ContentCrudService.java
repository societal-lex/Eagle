/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.service;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.infosys.lexauthoringservices.exception.BadRequestException;
import com.infosys.lexauthoringservices.model.Response;

public interface ContentCrudService {

	String createContentNode(String rootOrg, String org, Map<String, Object> requestMap) throws Exception;

	void createContentNodeForMigration(String rootOrg, String org, Map<String, Object> requestMap) throws Exception;

	void updateContentNode(String rootOrg, String org, String identifier, Map<String, Object> requestMap)
			throws Exception;

	Map<String, Object> getContentNode(String rootOrg, String identifier) throws Exception;

	Map<String, Object> getContentHierarchy(String identifier, String rootOrg, String org)
			throws BadRequestException, Exception;

	void updateContentHierarchy(String rootOrg, String org, Map<String, Object> requestMap, String migration)
			throws Exception;

//	Response publishContent(String identifier, String rootOrg, String org, String creatorEmail) throws Exception;

	Response statusChange(String identifier, Map<String, Object> requestBody) throws BadRequestException, Exception;

	void contentDelete(String identifier, String authorEmail, String rootOrg, String userType) throws Exception;

	Response externalContentPublish(String identifier, Map<String, Object> requestBody) throws Exception;

	Map<String, Object> getContentHierarchyFields(String identifier, String rootOrg, String org,
			Map<String, Object> requestMap) throws JsonParseException, JsonMappingException, IOException, Exception;

//	Response updatePlaylistNode(String rootOrg, String identifier, Map<String, Object> requestMap)throws Exception;

	Response extendContentExpiry(Map<String, Object> requestBody) throws Exception;

}
