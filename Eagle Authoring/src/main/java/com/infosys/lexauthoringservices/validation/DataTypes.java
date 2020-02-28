/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.validation;

public enum DataTypes {

	STRING("String"), LONG("Long"), BOOLEAN("Boolean"), LIST("List");

	private String dataType;

	public String get() {
		return dataType;
	}

	private DataTypes(String dataType) {
		this.dataType = dataType;
	}
}