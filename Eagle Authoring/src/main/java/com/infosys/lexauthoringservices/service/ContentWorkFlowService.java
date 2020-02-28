/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.service;

import java.util.Map;

import com.infosys.lexauthoringservices.model.Response;

public interface ContentWorkFlowService {

	Response upsertNewWorkFlow(Map<String, Object> requestBody) throws Exception;

	Response removeFromWorkFlow(Map<String, Object> requestBody) throws Exception;

	Response fetchWorkFlowData(Map<String, Object> requestBody) throws Exception;

	

}
