/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.validation;

public enum ValidationConstants {

	OPERATION("operation"), PROPERTY("property"), DATA_TYPE("dataType"), VALUE("value"),
	VALIDATE_BELOW("validateBelow"), VALIDATE_HERE("validateHere");

	private String validationConstant;

	public String get() {
		return validationConstant;
	}

	private ValidationConstants(String validationConstant) {
		this.validationConstant = validationConstant;
	}
}