/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.service;

import java.util.List;
import java.util.Map;

import com.infosys.lexauthoringservices.model.Response;

public interface FeatureAccessControlService {

	Map<String,Object> getFeature(String rootOrg,String identifier) throws Exception;
	
	String createFeatureNode(String rootOrg,String org, Map<String,Object> requestBody) throws Exception;
	
	Response updateFeatureNode(String rootOrg,String org, Map<String,Object> requestBody) throws Exception;
	
	Response deleteFeatureNode(String rootOrg,String org, Map<String,Object> requestBody) throws Exception;

	List<Map<String,Object>> fetchAllData(String rootOrg, String org)throws Exception;
	
	
}
