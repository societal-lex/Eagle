/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
/**
 * © 2017 - 2019 Infosys Limited, Bangalore, India. All Rights Reserved.
 * Version: 1.10
 * <p>
 * Except for any free or open source software components embedded in this Infosys proprietary software program (“Program”),
 * this Program is protected by copyright laws, international treaties and other pending or existing intellectual property rights in India,
 * the United States and other countries. Except as expressly permitted, any unauthorized reproduction, storage, transmission in any form or
 * by any means (including without limitation electronic, mechanical, printing, photocopying, recording or otherwise), or any distribution of
 * this Program, or any portion of it, may result in severe civil and criminal penalties, and will be prosecuted to the maximum extent possible
 * under the law.
 * <p>
 * Highly Confidential
 */
package com.infosys.lexauthoringservices.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infosys.lexauthoringservices.util.InterMLSearchIndexer;
import com.infosys.lexauthoringservices.util.LexLogger;
import com.infosys.lexauthoringservices.util.PublishPipeLineStage1;
import com.infosys.lexauthoringservices.util.SearchIndexer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.PartitionOffset;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SearchIndexerConsumer {
    LexLogger lexLogger = new LexLogger("SearchIndexerConsumer");

    ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Value("${infosys.spring.kafka.publisher.error.output.topic}")
    String publisherErrorTopic;
    @Value("${infosys.spring.kafka.search.indexer.error.output.topic}")
    String searchIndexerErrorTopic;
    @Autowired
    private SearchIndexer searchIndexer;
    @Autowired
    private InterMLSearchIndexer interMLSearchIndexer;
    @Autowired
    private PublishPipeLineStage1 publishPipeLineStage1;
    private String searchIndexerErrorTopicKey = "search-indexer-error";
    private String publisherErrorTopicKey = "publishpipeline-stage1-error";

    @KafkaListener(id = "searcher-indexer1", groupId = "consumer-group", topicPartitions = {@TopicPartition(topic = "learning-graph-events", partitions = "0")})
    public void listenSearch(ConsumerRecord<?,?> consumerRecord) {
        String message = String.valueOf(consumerRecord.value());
        System.out.println("$$$$$$$$$$$$$$$$MESSAGE OFFSET FROM SEARCH 1$$$$$$$$$$$$$$$");
        System.out.println(consumerRecord.offset());
        System.out.println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
        try {
            List<Map<String, Object>> failures = new ArrayList<>();
            List<Map<String, Object>> data = objectMapper.readValue(message, new TypeReference<Object>() {
            });
            failures = searchIndexer.processMessageEnvelope(data);
            if (!failures.isEmpty()) {
                kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, objectMapper.writeValueAsString(failures));
            }
        } catch (Exception e) {
            e.printStackTrace();
            kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, message + "--->" + e.getMessage());
        }
    }

    @KafkaListener(id = "searcher-indexer2", groupId = "consumer-group", topicPartitions = {@TopicPartition(topic = "learning-graph-events", partitions = "1")})
    public void listenSearch1(ConsumerRecord<?,?> consumerRecord) {
        String message = String.valueOf(consumerRecord.value());
        System.out.println("$$$$$$$$$$$$$$$$MESSAGE OFFSET FROM SEARCH 2$$$$$$$$$$$$$$$");
        System.out.println(consumerRecord.offset());
        System.out.println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
        try {
            List<Map<String, Object>> failures = new ArrayList<>();
            List<Map<String, Object>> data = objectMapper.readValue(message, new TypeReference<Object>() {
            });
            failures = searchIndexer.processMessageEnvelope(data);
            if (!failures.isEmpty()) {
                kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, objectMapper.writeValueAsString(failures));
            }
        } catch (Exception e) {
            e.printStackTrace();
            kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, message + "--->" + e.getMessage());
        }
    }

    @KafkaListener(id = "searcher-indexer3", groupId = "consumer-group", topicPartitions = {@TopicPartition(topic = "learning-graph-events", partitions = "2")})
    public void listenSearch2(ConsumerRecord<?,?> consumerRecord) {
        String message = String.valueOf(consumerRecord.value());
        System.out.println("$$$$$$$$$$$$$$$$MESSAGE OFFSET FROM SEARCH 3$$$$$$$$$$$$$$$");
        System.out.println(consumerRecord.offset());
        System.out.println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
        try {
            List<Map<String, Object>> failures = new ArrayList<>();
            List<Map<String, Object>> data = objectMapper.readValue(message, new TypeReference<Object>() {
            });
            failures = searchIndexer.processMessageEnvelope(data);
            if (!failures.isEmpty()) {
                kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, objectMapper.writeValueAsString(failures));
            }
        } catch (Exception e) {
            e.printStackTrace();
            kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, message + "--->" + e.getMessage());
        }
    }

    @KafkaListener(id = "searcher-indexer4", groupId = "consumer-group", topicPartitions = {@TopicPartition(topic = "learning-graph-events", partitions = "3")})
    public void listenSearch3(ConsumerRecord<?,?> consumerRecord) {
        String message = String.valueOf(consumerRecord.value());
        System.out.println("$$$$$$$$$$$$$$$$MESSAGE OFFSET FROM SEARCH 4$$$$$$$$$$$$$$$");
        System.out.println(consumerRecord.offset());
        System.out.println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
        try {
            List<Map<String, Object>> failures = new ArrayList<>();
            List<Map<String, Object>> data = objectMapper.readValue(message, new TypeReference<Object>() {
            });
            failures = searchIndexer.processMessageEnvelope(data);
            if (!failures.isEmpty()) {
                kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, objectMapper.writeValueAsString(failures));
            }
        } catch (Exception e) {
            e.printStackTrace();
            kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, message + "--->" + e.getMessage());
        }
    }

    @KafkaListener(clientIdPrefix = "publisher1", groupId = "consumer-group", topicPartitions = {@TopicPartition(topic = "publishpipeline-stage1", partitions = "0")})
    public void listenPublish(ConsumerRecord<?,?> consumerRecord) {
        String message = String.valueOf(consumerRecord.value());
        UUID uuid = UUID.randomUUID();
        System.out.println(uuid + "$$$$$$$$$$$$$$$$MESSAGE OFFSET FROM PUBLISH$$$$$$$$$$$$$$$");
        System.out.println(uuid + "" + consumerRecord.offset());
        System.out.println(uuid + "$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
        try {
            Map<String, Object> data = objectMapper.readValue(message, new TypeReference<Object>() {
            });
            if (!publishPipeLineStage1.processMessage(data,uuid)) {
                kafkaTemplate.send(publisherErrorTopic, publisherErrorTopicKey, message);
            }
        } catch (Exception e) {
            e.printStackTrace();
            kafkaTemplate.send(publisherErrorTopic, publisherErrorTopicKey, message + "--->" + e.getMessage());
        }
    }

    @KafkaListener(id = "inter-ml-search-indexer1", groupId = "consumer-group", topicPartitions = {@TopicPartition(topic = "learning-graph-events", partitions = {"0","1","2","3"})})
    public void listenMLSearch(ConsumerRecord<?,?> consumerRecord) {
        String message = String.valueOf(consumerRecord.value());
        System.out.println("$$$$$$$$$$$$$$$$MESSAGE OFFSET FROM ML SEARCH$$$$$$$$$$$$$$$");
        System.out.println(consumerRecord.offset());
        System.out.println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
        try {
            List<Map<String, Object>> failures = new ArrayList<>();
            List<Map<String, Object>> data = objectMapper.readValue(message, new TypeReference<Object>() {
            });
            failures = interMLSearchIndexer.processMessageEnvelope(data);
            if (!failures.isEmpty()) {
                kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, objectMapper.writeValueAsString(failures));
            }
        } catch (Exception e) {
            e.printStackTrace();
            kafkaTemplate.send(searchIndexerErrorTopic, searchIndexerErrorTopicKey, message + "--->" + e.getMessage());
        }
    }
}
