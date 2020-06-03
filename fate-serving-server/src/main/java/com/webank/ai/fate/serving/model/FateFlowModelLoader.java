/*
 * Copyright 2019 The FATE Authors. All Rights Reserved.
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

package com.webank.ai.fate.serving.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.webank.ai.fate.register.router.RouterService;
import com.webank.ai.fate.register.url.URL;
import com.webank.ai.fate.serving.core.bean.Context;
import com.webank.ai.fate.serving.core.bean.Dict;
import com.webank.ai.fate.serving.core.model.ModelProcessor;
import com.webank.ai.fate.serving.core.utils.HttpClientPool;
import com.webank.ai.fate.serving.federatedml.PipelineModelProcessor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FateFlowModelLoader extends AbstractModelLoader<Map<String, byte[]>> {
    private static final Logger logger = LoggerFactory.getLogger(FateFlowModelLoader.class);

    @Autowired(required = false)
    private RouterService routerService;

    @Autowired
    private Environment environment;

    @Override
    protected byte[] serialize(Context context, Map<String, byte[]> data) {
        Map<String, String> result = Maps.newHashMap();
        if (data != null) {
            data.forEach((k, v) -> {
                String base64String = new String(Base64.getEncoder().encode(v));
                result.put(k, base64String);
            });
            return JSON.toJSONString(result).getBytes();
        }
        return null;
    }

    @Override
    protected Map<String, byte[]> unserialize(Context context, byte[] data) {
        Map<String, byte[]> result = Maps.newHashMap();
        if (data != null) {
            String dataString = new String(data);
            Map originData = JSON.parseObject(dataString, Map.class);
            if (originData != null) {
                originData.forEach((k, v) -> {
                    result.put(k.toString(), Base64.getDecoder().decode(v.toString()));
                });
                return result;
            }
        }
        return null;
    }

    @Override
    protected ModelProcessor initPipeLine(Context context, Map<String, byte[]> stringMap) {
        if (stringMap != null) {
            PipelineModelProcessor modelProcessor = new PipelineModelProcessor();
            modelProcessor.initModel(stringMap);
            return modelProcessor;
        } else {
            return null;
        }
    }

    @Override
    protected Map<String, byte[]> doLoadModel(Context context, ModelLoaderParam modelLoaderParam) {

        logger.info("read model, name: {} namespace: {}", modelLoaderParam.tableName, modelLoaderParam.nameSpace);
        try {
            String requestUrl = "";

            if (routerService != null) {
                URL url = URL.valueOf("flow/online/transfer");
                List<URL> urls = routerService.router(url);
                if (urls == null || urls.isEmpty()) {
                    logger.info("register url not found, {}", url);
                } else {
                    url = urls.get(0);
                    requestUrl = url.toFullString();
                }
            }

            if (StringUtils.isBlank(requestUrl)) {
                requestUrl = environment.getProperty(Dict.MODEL_TRANSFER_URL);
                logger.info("use profile model.transfer.url, {}", requestUrl);
            }

            if (StringUtils.isBlank(requestUrl)) {
                logger.info("roll address not found");
                return null;
            }

            Map<String, Object> requestData = new HashMap<>(8);
            requestData.put("name", modelLoaderParam.tableName);
            requestData.put("namespace", modelLoaderParam.nameSpace);

            long start = System.currentTimeMillis();
            String responseBody = HttpClientPool.transferPost(requestUrl, requestData);
            long end = System.currentTimeMillis();

            if (logger.isDebugEnabled()) {
                logger.debug("{}|{}|{}|{}", requestUrl, start, end, (end - start) + " ms");
            }

            if (StringUtils.isEmpty(responseBody)) {
                logger.info("read model fail, {}, {}", modelLoaderParam.tableName, modelLoaderParam.nameSpace);
                return null;
            }

            JSONObject responseData = JSONObject.parseObject(responseBody);
            if (responseData.getInteger(Dict.RET_CODE) != 0) {
                logger.info("read model fail, {}, {}, {}", modelLoaderParam.tableName, modelLoaderParam.nameSpace, responseData.getString("retmsg"));
                return null;
            }

            Map<String, byte[]> resultMap = new HashMap<>(8);
            Map<String, Object> dataMap = responseData.getJSONObject(Dict.DATA);
            if (dataMap == null || dataMap.isEmpty()) {
                logger.info("read model fail, {}, {}, {}", modelLoaderParam.tableName, modelLoaderParam.nameSpace, dataMap);
                return null;
            }

            for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                resultMap.put(entry.getKey(), Base64.getDecoder().decode(String.valueOf(entry.getValue())));
            }

            return resultMap;
        } catch (Exception e) {
            logger.error("get model info from fateflow error", e);
        }
        return null;
    }


}