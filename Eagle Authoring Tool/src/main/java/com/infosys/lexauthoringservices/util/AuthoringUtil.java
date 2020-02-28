/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.xml.bind.DatatypeConverter;

public class AuthoringUtil {

	public static BigInteger md5HashGenerator(String identifierString) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.update(identifierString.getBytes());
			byte[] digest = md.digest();
			String myHash = DatatypeConverter.printHexBinary(digest).toUpperCase();
			BigInteger identifierStringToBigInteger = new BigInteger(myHash, 16);
			return identifierStringToBigInteger;
		} catch (NoSuchAlgorithmException ex) {
			return null;
		}
	}
}
