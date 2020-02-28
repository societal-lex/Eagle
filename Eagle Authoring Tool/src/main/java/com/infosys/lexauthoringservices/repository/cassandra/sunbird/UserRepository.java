/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.repository.cassandra.sunbird;

import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;

import com.infosys.lexauthoringservices.model.cassandra.User;

public interface UserRepository extends CassandraRepository<User, String> {

	@Query("select * from user where email=?0")
	public User findByEmail(String email);
}
