/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LexServerProperties {

	@Value("${content.id.prefix}")
	private String contentIdPrefix;
	
	@Value("${feature.id.prefix}")
	private String featureIdPrefix;

	@Value("${content.service.url}")
	private String contentServiceUrl;
	
	@Value("${topic.service.url}")
	private String topicServiceUrl;
	
	@Value("${access.control.url}")
	private String accessUrlPrefix;
	
	@Value("${access.control.url.user}")
	private String accessUrlPostFix;
	
	@Value("${content.service.publish.url}")
    private String contentServicePublishUrl;

	@Value("${content.service.zip.url}")
	private String contentServiceZipUrl;

	@Value("${email.service.url}")
	private String emailServiceUrl;
	
	public String getFeatureIdPrefix() {
		return featureIdPrefix;
	}

	public String getAccessUrlPostFix() {
		return accessUrlPostFix;
	}

	public String getContentServiceUrl() {
		return contentServiceUrl;
	}

	public String getTopicServiceUrl() {
		return topicServiceUrl;
	}

	public String getContentIdPrefix() {
		return contentIdPrefix;
	}

	public String getAccessUrlPrefix() {
		return accessUrlPrefix;
	}

    public String getContentServicePublishUrl() {
        return contentServicePublishUrl;
    }

	public String getContentServiceZipUrl() {
		return contentServiceZipUrl;
	}

	public String getEmailServiceUrl() {
		return emailServiceUrl;
	}
}
