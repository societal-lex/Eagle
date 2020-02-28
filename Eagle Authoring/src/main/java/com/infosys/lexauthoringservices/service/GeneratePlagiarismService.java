/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.service;

import java.io.File;

import org.springframework.stereotype.Service;

import com.infosys.lexauthoringservices.exception.ApplicationLogicError;


public interface GeneratePlagiarismService {

	public File generatePlagiarismReport(String identifier, String domain,String rootOrg,String org) throws ApplicationLogicError;

}
