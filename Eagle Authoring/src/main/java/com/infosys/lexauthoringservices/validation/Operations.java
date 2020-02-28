/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.validation;

public enum Operations {

	EQUALS("equals"), NOT_EQUALS("notequals"), GREATER_THAN("greaterthan"), LESS_THAN("lessthan"), NOT_NULL("notnull"),
	IN("in"), HIT_URL("hiturl"), CONTAINS("contains"), NOT_CONTAINS("notcontains"), EMPTY("empty");

	private String operation;

	public String get() {
		return operation;
	}

	private Operations(String operation) {
		this.operation = operation;
	}
}