/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.util;

public enum ContentOperation {

	UPDATE("update"), UPDATE_HIERARCHY("updateHierarchy"), FETCH("fetch"), FETCH_HIERARCHY("fetchHierarchy"),

	STATUS_CHANGE("statusChange");

	private String contentOperation;

	public String get() {
		return contentOperation;
	}

	private ContentOperation(String operation) {
		this.contentOperation = operation;
	}

}