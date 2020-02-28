/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.service;

import java.util.Map;
import java.util.Set;

public interface ValidationsService {

//	Map<String, Set<String>> validations(List<Map<String, Object>> contentMetas)
//			throws JsonParseException, JsonMappingException, IOException;

//	Set<String> validations(Map<String, Object> contentMeta)
//			throws JsonParseException, JsonMappingException, IOException;

	Map<String, Set<String>> validationsV2(String rootOrg, Map<String, Object> contentMeta) throws Exception;

	Set<String> getRequiredFieldsForRootOrg(String rootOrg) throws Exception;

	Map<String, Object> getValidationNode(String identifier) throws Exception;

	void putValidationNode(String identifier, Map<String, Object> validationNode) throws Exception;

	void validateMetaFields(String rootOrg, Map<String, Object> contentMeta) throws Exception;

	Map<String, Object> getValidationRelation(String startNodeId, String endNodeId);

	void putValidationRelation(String startNodeId, String endNodeId, Map<String, Object> relationMap) throws Exception;

}
