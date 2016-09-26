/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.compatibility.module.spring.security;

import org.mule.runtime.module.spring.security.CustomSecManagerHttpFilterFunctionalTestCase;

import org.junit.Ignore;

@Ignore("MULE-9705 - Migrate Spring security tests")
public class SSCustomSecManagerHttpFilterFunctionalTestCase extends CustomSecManagerHttpFilterFunctionalTestCase {

  @Override
  protected String getConfigFile() {
    return "spring-security/custom-security-manager-test.xml";
  }

}
