/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.model;

public class UpdateRelationRequestV2 {

	private String startNodeRootOrg;
	private String startNodeId;

	private String endNodeRootOrg;
	private String endNodeId;

	private Integer index;

	public UpdateRelationRequestV2(String startNodeRootOrg, String startNodeId, String endNodeRootOrg, String endNodeId,
			Integer index) {

		this.startNodeRootOrg = startNodeRootOrg;
		this.startNodeId = startNodeId;
		this.endNodeRootOrg = endNodeRootOrg;
		this.endNodeId = endNodeId;
		this.index = index;
	}

	public String getStartNodeRootOrg() {
		return startNodeRootOrg;
	}

	public void setStartNodeRootOrg(String startNodeRootOrg) {
		this.startNodeRootOrg = startNodeRootOrg;
	}

	public String getEndNodeRootOrg() {
		return endNodeRootOrg;
	}

	public void setEndNodeRootOrg(String endNodeRootOrg) {
		this.endNodeRootOrg = endNodeRootOrg;
	}

	public String getStartNodeId() {
		return startNodeId;
	}

	public void setStartNodeId(String startNodeId) {
		this.startNodeId = startNodeId;
	}

	public String getEndNodeId() {
		return endNodeId;
	}

	public void setEndNodeId(String endNodeId) {
		this.endNodeId = endNodeId;
	}

	public Integer getIndex() {
		return index;
	}

	public void setIndex(Integer index) {
		this.index = index;
	}
}