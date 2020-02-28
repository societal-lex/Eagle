/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.config;

import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Neo4jConfig {

	@Value("${neo4j.url}")
	private String neo4jHost;

	@Value("${neo4j.username}")
	private String neo4jUserName;

	@Value("${neo4j.password}")
	private String neo4jPassword;

	@Value("${neo4j.auth.enable}")
	private String neo4jAuthEnable;

	@Bean
	public Driver Neo4jDriver() {

		if (Boolean.parseBoolean(neo4jAuthEnable)) {
			return GraphDatabase.driver(neo4jHost, AuthTokens.basic(neo4jUserName, neo4jPassword));
		} else {
			return GraphDatabase.driver(neo4jHost);
		}
	}
}
