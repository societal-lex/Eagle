/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.validation.model;

import org.neo4j.driver.v1.types.Node;
import org.neo4j.driver.v1.types.Relationship;

public class Path {

	private Node fromNode;

	private Node toNode;

	private Relationship relation;

	public Path(Node fromNode, Node toNode, Relationship relation) {
		this.fromNode = fromNode;
		this.toNode = toNode;
		this.relation = relation;
	}

	public Node getFromNode() {
		return fromNode;
	}

	public void setFromNode(Node fromNode) {
		this.fromNode = fromNode;
	}

	public Node getToNode() {
		return toNode;
	}

	public void setToNode(Node toNode) {
		this.toNode = toNode;
	}

	public Relationship getRelation() {
		return relation;
	}

	public void setRelation(Relationship relation) {
		this.relation = relation;
	}

}
