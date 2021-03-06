/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.kafka;

import static org.hamcrest.MatcherAssert.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.beam.model.expansion.v1.ExpansionApi;
import org.apache.beam.model.pipeline.v1.ExternalTransforms;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.runners.core.construction.ReadTranslation;
import org.apache.beam.runners.core.construction.expansion.ExpansionService;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.vendor.grpc.v1p13p1.com.google.protobuf.ByteString;
import org.apache.beam.vendor.grpc.v1p13p1.io.grpc.stub.StreamObserver;
import org.apache.beam.vendor.guava.v20_0.com.google.common.base.Charsets;
import org.apache.beam.vendor.guava.v20_0.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v20_0.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v20_0.com.google.common.collect.ImmutableMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.hamcrest.Matchers;
import org.junit.Test;

/** Tests for building {@link KafkaIO} externally via the ExpansionService. */
public class KafkaIOExternalTest {
  @Test
  public void testConstructKafkaIO() throws Exception {
    List<String> topics = ImmutableList.of("topic1", "topic2");
    String keyDeserializer = "org.apache.kafka.common.serialization.ByteArrayDeserializer";
    String valueDeserializer = "org.apache.kafka.common.serialization.LongDeserializer";
    ImmutableMap<String, String> consumerConfig =
        ImmutableMap.<String, String>builder()
            .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "server1:port,server2:port")
            .put("key2", "value2")
            .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer)
            .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer)
            .build();

    ExternalTransforms.ExternalConfigurationPayload payload =
        ExternalTransforms.ExternalConfigurationPayload.newBuilder()
            .putConfiguration(
                "topics",
                ExternalTransforms.ConfigValue.newBuilder()
                    .addCoderUrn("beam:coder:iterable:v1")
                    .addCoderUrn("beam:coder:bytes:v1")
                    .setPayload(ByteString.copyFrom(listAsBytes(topics)))
                    .build())
            .putConfiguration(
                "consumer_config",
                ExternalTransforms.ConfigValue.newBuilder()
                    .addCoderUrn("beam:coder:iterable:v1")
                    .addCoderUrn("beam:coder:kv:v1")
                    .addCoderUrn("beam:coder:bytes:v1")
                    .addCoderUrn("beam:coder:bytes:v1")
                    .setPayload(ByteString.copyFrom(mapAsBytes(consumerConfig)))
                    .build())
            .putConfiguration(
                "key_deserializer",
                ExternalTransforms.ConfigValue.newBuilder()
                    .addCoderUrn("beam:coder:bytes:v1")
                    .setPayload(ByteString.copyFrom(encodeString(keyDeserializer)))
                    .build())
            .putConfiguration(
                "value_deserializer",
                ExternalTransforms.ConfigValue.newBuilder()
                    .addCoderUrn("beam:coder:bytes:v1")
                    .setPayload(ByteString.copyFrom(encodeString(valueDeserializer)))
                    .build())
            .build();

    RunnerApi.Components defaultInstance = RunnerApi.Components.getDefaultInstance();
    ExpansionApi.ExpansionRequest request =
        ExpansionApi.ExpansionRequest.newBuilder()
            .setComponents(defaultInstance)
            .setTransform(
                RunnerApi.PTransform.newBuilder()
                    .setUniqueName("test")
                    .setSpec(
                        RunnerApi.FunctionSpec.newBuilder()
                            .setUrn("beam:external:java:kafka:read:v1")
                            .setPayload(payload.toByteString())))
            .setNamespace("test_namespace")
            .build();

    ExpansionService expansionService = new ExpansionService();
    TestStreamObserver<ExpansionApi.ExpansionResponse> observer = new TestStreamObserver<>();
    expansionService.expand(request, observer);

    ExpansionApi.ExpansionResponse result = observer.result;
    RunnerApi.PTransform transform = result.getTransform();
    assertThat(
        transform.getSubtransformsList(),
        Matchers.contains(
            "test_namespacetest/KafkaIO.Read", "test_namespacetest/Remove Kafka Metadata"));
    assertThat(transform.getInputsCount(), Matchers.is(0));
    assertThat(transform.getOutputsCount(), Matchers.is(1));

    RunnerApi.PTransform kafkaComposite =
        result.getComponents().getTransformsOrThrow(transform.getSubtransforms(0));
    RunnerApi.PTransform kafkaRead =
        result.getComponents().getTransformsOrThrow(kafkaComposite.getSubtransforms(0));
    RunnerApi.ReadPayload readPayload =
        RunnerApi.ReadPayload.parseFrom(kafkaRead.getSpec().getPayload());
    KafkaUnboundedSource source =
        (KafkaUnboundedSource) ReadTranslation.unboundedSourceFromProto(readPayload);
    KafkaIO.Read spec = source.getSpec();

    assertThat(spec.getConsumerConfig(), Matchers.is(consumerConfig));
    assertThat(spec.getTopics(), Matchers.is(topics));
    assertThat(spec.getKeyDeserializer().getName(), Matchers.is(keyDeserializer));
    assertThat(spec.getValueDeserializer().getName(), Matchers.is(valueDeserializer));
  }

  private static byte[] listAsBytes(List<String> stringList) throws IOException {
    IterableCoder<byte[]> coder = IterableCoder.of(ByteArrayCoder.of());
    List<byte[]> bytesList =
        stringList.stream().map(KafkaIOExternalTest::rawBytes).collect(Collectors.toList());
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    coder.encode(bytesList, baos);
    return baos.toByteArray();
  }

  private static byte[] mapAsBytes(Map<String, String> stringMap) throws IOException {
    IterableCoder<KV<byte[], byte[]>> coder =
        IterableCoder.of(KvCoder.of(ByteArrayCoder.of(), ByteArrayCoder.of()));
    List<KV<byte[], byte[]>> bytesList =
        stringMap.entrySet().stream()
            .map(kv -> KV.of(rawBytes(kv.getKey()), rawBytes(kv.getValue())))
            .collect(Collectors.toList());
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    coder.encode(bytesList, baos);
    return baos.toByteArray();
  }

  private static byte[] encodeString(String str) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ByteArrayCoder.of().encode(rawBytes(str), baos);
    return baos.toByteArray();
  }

  private static byte[] rawBytes(String str) {
    Preconditions.checkNotNull(str, "String must not be null.");
    return str.getBytes(Charsets.UTF_8);
  }

  private static class TestStreamObserver<T> implements StreamObserver<T> {

    private T result;

    @Override
    public void onNext(T t) {
      result = t;
    }

    @Override
    public void onError(Throwable throwable) {
      throw new RuntimeException("Should not happen", throwable);
    }

    @Override
    public void onCompleted() {}
  }
}
