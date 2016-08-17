/*
 * Copyright 2015-2016 http://hsweb.me
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hsweb.web.oauth2.service;

import org.hsweb.web.oauth2.dao.OAuth2ClientMapper;
import org.hsweb.web.oauth2.po.OAuth2Client;
import org.hsweb.web.service.impl.AbstractServiceImpl;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service("oAuth2ClientService")
public class OAuth2ClientServiceImpl extends AbstractServiceImpl<OAuth2Client, String> implements OAuth2ClientService {

    @Resource
    private OAuth2ClientMapper oAuth2ClientMapper;

    @Override
    protected OAuth2ClientMapper getMapper() {
        return oAuth2ClientMapper;
    }
}
