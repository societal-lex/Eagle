/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.model.cassandra;

import java.util.Date;
import java.util.List;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

@Table("master_values")
public class MasterValues {

	@PrimaryKey
	@Column("entity")
	private String entity;

	@Column("date_created")
	private Date dateCreated;

	@Column("date_modified")
	private Date dateModified;

	@Column("values")
	private List<String> values;

	public String getEntity() {
		return entity;
	}

	public void setEntity(String entity) {
		this.entity = entity;
	}

	public Date getDateCreated() {
		return dateCreated;
	}

	public void setDateCreated(Date dateCreated) {
		this.dateCreated = dateCreated;
	}

	public Date getDateModified() {
		return dateModified;
	}

	public void setDateModified(Date dateModified) {
		this.dateModified = dateModified;
	}

	public List<String> getValues() {
		return values;
	}

	public void setValues(List<String> values) {
		this.values = values;
	}

	public MasterValues(String entity, Date dateCreated, Date dateModified, List<String> values) {
		super();
		this.entity = entity;
		this.dateCreated = dateCreated;
		this.dateModified = dateModified;
		this.values = values;
	}

	public MasterValues() {
		super();
	}

}
