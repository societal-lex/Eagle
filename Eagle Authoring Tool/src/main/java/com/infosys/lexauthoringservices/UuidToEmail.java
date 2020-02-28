/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class UuidToEmail {

	RestTemplate restTemplate = new RestTemplate();

	ObjectMapper mapper = new ObjectMapper();

	@SuppressWarnings("unchecked")
	public Map<String, String> createEmailToUUIDMapping() throws JsonParseException, JsonMappingException, IOException {

		Map<String, String> emailToUUIDMapping = new HashMap<>();

		HttpHeaders headers = new HttpHeaders();
		headers.set("Client_Id", "1002");
		headers.set("Api_Key", "VALUE");

		String url = "http://URL_DEFAULT:3010/v1/lex/emailFromUserId?pageSize=5000&recordLink=";

		String recordLink = "";

		while (true) {

			try {
				ResponseEntity<String> response = restTemplate.exchange(url + recordLink, HttpMethod.GET,
						new HttpEntity<Object>(headers), String.class);
				Map<String, Object> responseMap = mapper.readValue(response.getBody(),
						new TypeReference<Map<String, Object>>() {
						});

				responseMap = (Map<String, Object>) responseMap.get("result");
				recordLink = responseMap.get("nextRecord").toString();

				List<Map<String, Object>> dataList = (List<Map<String, Object>>) responseMap.get("dataList");

				for (Map<String, Object> dataPoint : dataList) {
					emailToUUIDMapping.put(dataPoint.get("email").toString(), dataPoint.get("userId").toString());
				}
				if (recordLink.equals("-1")) {
					break;
				}

			} catch (HttpStatusCodeException ex) {
				System.out.println(ex.getResponseBodyAsString());
			}

		}
		return emailToUUIDMapping;
	}
}
