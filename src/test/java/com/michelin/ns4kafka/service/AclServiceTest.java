/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.michelin.ns4kafka.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.michelin.ns4kafka.model.AccessControlEntry;
import com.michelin.ns4kafka.model.Metadata;
import com.michelin.ns4kafka.model.Namespace;
import com.michelin.ns4kafka.repository.AccessControlEntryRepository;
import com.michelin.ns4kafka.service.executor.AccessControlEntryAsyncExecutor;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AclServiceTest {
    @Mock
    AccessControlEntryRepository accessControlEntryRepository;

    @Mock
    AccessControlEntryAsyncExecutor accessControlEntryAsyncExecutor;

    @Mock
    ApplicationContext applicationContext;

    @Mock
    NamespaceService namespaceService;

    @InjectMocks
    AclService aclService;

    @Test
    void shouldNotValidateAcl() {
        Namespace namespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("namespace").cluster("local").build())
                .build();

        AccessControlEntry badAcl = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("namespace")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("test")
                        .grantedTo("target-ns")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("target-ns")).thenReturn(Optional.empty());
        when(accessControlEntryRepository.findAll()).thenReturn(List.of());

        List<String> actual = aclService.validate(badAcl, namespace);
        assertLinesMatch(
                List.of(
                        "Invalid value \"CONNECT\" for field \"resourceType\": "
                                + "value must be one of \"TOPIC, CONNECT_CLUSTER\".",
                        "Invalid value \"OWNER\" for field \"permission\": value must be one of \"READ, WRITE\".",
                        "Invalid value \"target-ns\" for field \"grantedTo\": resource not found.",
                        "Invalid value \"test/PREFIXED\" for fields \"resource/resourcePatternType\": "
                                + "cannot grant ACL because namespace is not owner of the top level resource."),
                actual);
    }

    @Test
    void shouldNotValidateAclBecauseSelfGranted() {
        Namespace namespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("namespace").cluster("local").build())
                .build();

        AccessControlEntry badAcl = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("namespace")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("test")
                        .grantedTo("namespace")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("namespace")).thenReturn(Optional.of(namespace));
        when(accessControlEntryRepository.findAll()).thenReturn(List.of());

        List<String> actual = aclService.validate(badAcl, namespace);
        assertLinesMatch(
                List.of(
                        "Invalid value \"namespace\" for field \"grantedTo\": cannot grant ACL to yourself.",
                        "Invalid value \"test/PREFIXED\" for fields \"resource/resourcePatternType\": "
                                + "cannot grant ACL because namespace is not owner of the top level resource."),
                actual);
    }

    @Test
    void shouldNotValidateAclBecauseNotOwnerOfTopLevelResourceHavingBadPrefix() {
        Namespace ns = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("namespace").cluster("local").build())
                .build();

        Namespace targetNamespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("target-ns").cluster("local").build())
                .build();

        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("namespace")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("main")
                        .grantedTo("target-ns")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("target-ns")).thenReturn(Optional.of(targetNamespace));
        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(AccessControlEntry.builder()
                        .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                                .resourceType(AccessControlEntry.ResourceType.TOPIC)
                                .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                                .permission(AccessControlEntry.Permission.OWNER)
                                .resource("main.sub")
                                .grantedTo("namespace")
                                .build())
                        .metadata(Metadata.builder().cluster("local").build())
                        .build()));

        List<String> actual = aclService.validate(accessControlEntry, ns);
        assertLinesMatch(
                List.of("Invalid value \"main/PREFIXED\" for fields \"resource/resourcePatternType\": "
                        + "cannot grant ACL because namespace is not owner of the top level resource."),
                actual);
    }

    @Test
    void shouldNotValidateAclBecauseNotOwnerOfTopLevelResourceHavingBadLiteral() {
        Namespace namespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("namespace").cluster("local").build())
                .build();

        Namespace targetNamespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("target-ns").cluster("local").build())
                .build();

        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("namespace")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("resource2")
                        .grantedTo("target-ns")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("target-ns")).thenReturn(Optional.of(targetNamespace));
        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(AccessControlEntry.builder()
                        .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                                .resourceType(AccessControlEntry.ResourceType.TOPIC)
                                .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                                .permission(AccessControlEntry.Permission.OWNER)
                                .resource("resource1")
                                .grantedTo("namespace")
                                .build())
                        .metadata(Metadata.builder().cluster("local").build())
                        .build()));

        List<String> actual = aclService.validate(accessControlEntry, namespace);
        assertLinesMatch(
                List.of("Invalid value \"resource2/LITERAL\" for fields \"resource/resourcePatternType\": "
                        + "cannot grant ACL because namespace is not owner of the top level resource."),
                actual);
    }

    @Test
    void shouldValidateAclBecauseOwnerOfLiteral() {
        Namespace namespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("namespace").cluster("local").build())
                .build();

        Namespace targetNamespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("target-ns").cluster("local").build())
                .build();

        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("namespace")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("resource1")
                        .grantedTo("target-ns")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("target-ns")).thenReturn(Optional.of(targetNamespace));
        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(AccessControlEntry.builder()
                        .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                                .resourceType(AccessControlEntry.ResourceType.TOPIC)
                                .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                                .permission(AccessControlEntry.Permission.OWNER)
                                .resource("resource1")
                                .grantedTo("namespace")
                                .build())
                        .metadata(Metadata.builder().cluster("local").build())
                        .build()));

        List<String> actual = aclService.validate(accessControlEntry, namespace);
        assertTrue(actual.isEmpty());
    }

    @Test
    void shouldValidateAclBecauseOwnerOfPrefix() {
        Namespace namespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("namespace").cluster("local").build())
                .build();

        Namespace targetNamespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("target-ns").build())
                .build();

        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("namespace")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("main.sub")
                        .grantedTo("target-ns")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("target-ns")).thenReturn(Optional.of(targetNamespace));
        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(AccessControlEntry.builder()
                        .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                                .resourceType(AccessControlEntry.ResourceType.TOPIC)
                                .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                                .permission(AccessControlEntry.Permission.OWNER)
                                .resource("main")
                                .grantedTo("namespace")
                                .build())
                        .metadata(Metadata.builder().cluster("local").build())
                        .build()));

        List<String> actual = aclService.validate(accessControlEntry, namespace);
        assertTrue(actual.isEmpty());
    }

    @Test
    void shouldValidateAclWhenGrantedToAll() {
        Namespace namespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder().build())
                .metadata(Metadata.builder().name("namespace").cluster("local").build())
                .build();

        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("namespace")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("main.sub")
                        .grantedTo("*")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("*")).thenReturn(Optional.empty());
        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(AccessControlEntry.builder()
                        .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                                .resourceType(AccessControlEntry.ResourceType.TOPIC)
                                .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                                .permission(AccessControlEntry.Permission.OWNER)
                                .resource("main")
                                .grantedTo("namespace")
                                .build())
                        .metadata(Metadata.builder().cluster("local").build())
                        .build()));

        List<String> actual = aclService.validate(accessControlEntry, namespace);
        assertTrue(actual.isEmpty());
    }

    @Test
    void shouldNotValidateAclWhenGrantedToAllByProtectedNamespace() {
        Namespace namespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder()
                        .protectionEnabled(Boolean.TRUE)
                        .build())
                .metadata(Metadata.builder().name("namespace").cluster("local").build())
                .build();

        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("namespace")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("main.sub")
                        .grantedTo("*")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("*")).thenReturn(Optional.empty());
        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(AccessControlEntry.builder()
                        .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                                .resourceType(AccessControlEntry.ResourceType.TOPIC)
                                .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                                .permission(AccessControlEntry.Permission.OWNER)
                                .resource("main")
                                .grantedTo("namespace")
                                .build())
                        .metadata(Metadata.builder().cluster("local").build())
                        .build()));

        List<String> actual = aclService.validate(accessControlEntry, namespace);
        assertEquals(1, actual.size());
        assertEquals(
                "Invalid \"apply\" operation: " + "protected namespace can not grant public ACL.", actual.getFirst());
    }

    @Test
    void shouldNotValidateAclWhenGrantedToPublicNamespaceByProtectedNamespace() {
        Namespace protectedNamespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder()
                        .protectionEnabled(Boolean.TRUE)
                        .build())
                .metadata(
                        Metadata.builder().name("protected-ns").cluster("local").build())
                .build();

        Namespace publicNamespace = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder()
                        .protectionEnabled(Boolean.FALSE)
                        .build())
                .metadata(Metadata.builder().name("public-ns").cluster("local").build())
                .build();

        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("protected-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("main.sub")
                        .grantedTo("public-ns")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("public-ns")).thenReturn(Optional.of(publicNamespace));
        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(AccessControlEntry.builder()
                        .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                                .resourceType(AccessControlEntry.ResourceType.TOPIC)
                                .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                                .permission(AccessControlEntry.Permission.OWNER)
                                .resource("main")
                                .grantedTo("protected-ns")
                                .build())
                        .metadata(Metadata.builder().cluster("local").build())
                        .build()));

        List<String> actual = aclService.validate(accessControlEntry, protectedNamespace);
        assertEquals(1, actual.size());
        assertEquals(
                "Invalid \"apply\" operation: " + "protected namespace can only grant ACL to protected namespaces.",
                actual.getFirst());
    }

    @Test
    void shouldValidateAclWhenGrantedToProtectedNamespaceByProtectedNamespace() {
        Namespace protectedNamespace1 = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder()
                        .protectionEnabled(Boolean.TRUE)
                        .build())
                .metadata(Metadata.builder()
                        .name("protected-ns1")
                        .cluster("local")
                        .build())
                .build();

        Namespace protectedNamespace2 = Namespace.builder()
                .spec(Namespace.NamespaceSpec.builder()
                        .protectionEnabled(Boolean.TRUE)
                        .build())
                .metadata(Metadata.builder()
                        .name("protected-ns2")
                        .cluster("local")
                        .build())
                .build();

        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("protected-ns1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("main.sub")
                        .grantedTo("protected-ns2")
                        .build())
                .build();

        when(applicationContext.getBean(NamespaceService.class)).thenReturn(namespaceService);
        when(namespaceService.findByName("protected-ns2")).thenReturn(Optional.of(protectedNamespace2));
        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(AccessControlEntry.builder()
                        .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                                .resourceType(AccessControlEntry.ResourceType.TOPIC)
                                .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                                .permission(AccessControlEntry.Permission.OWNER)
                                .resource("main")
                                .grantedTo("protected-ns1")
                                .build())
                        .metadata(Metadata.builder().cluster("local").build())
                        .build()));

        List<String> actual = aclService.validate(accessControlEntry, protectedNamespace1);
        assertTrue(actual.isEmpty());
    }

    @Test
    void shouldValidateAsAdminUpdatingExistingAcl() {
        AccessControlEntry accessControlEntry = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-name")
                        .namespace("target-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.READ)
                        .resource("main.sub")
                        .grantedTo("target-ns")
                        .build())
                .build();

        Namespace namespace = Namespace.builder()
                .metadata(Metadata.builder().name("target-ns").cluster("local").build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(accessControlEntry));

        List<String> actual = aclService.validateSelfAssignedAdmin(accessControlEntry, namespace);

        assertTrue(actual.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"project1,project2_t1,proj,project2", "project1.abc,project1.def_ghi,project1_,project1_def"})
    void shouldValidateFailAsAdminWhenAclOverlapAsParent(
            String existingA, String existingB, String toCreateA, String toCreateB) {
        AccessControlEntry aceTopicPrefixedOwnerOtherNsToOtherNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-existing1")
                        .namespace("other-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource(existingA)
                        .grantedTo("other-ns")
                        .build())
                .build();

        AccessControlEntry aceTopicLiteralOwnerOtherNsToOtherNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-existing2")
                        .namespace("other-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource(existingB)
                        .grantedTo("other-ns")
                        .build())
                .build();

        Namespace namespace = Namespace.builder()
                .metadata(Metadata.builder().name("target-ns").cluster("local").build())
                .build();

        AccessControlEntry aceTopicPrefixedOwnerTargetNsToTargetNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-tocreate")
                        .namespace("target-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource(toCreateA)
                        .grantedTo("target-ns")
                        .build())
                .build();

        AccessControlEntry aceTopicPrefixedOwnerTargetNsToTargetNs2 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-tocreate")
                        .namespace("target-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource(toCreateB)
                        .grantedTo("target-ns")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(aceTopicPrefixedOwnerOtherNsToOtherNs, aceTopicLiteralOwnerOtherNsToOtherNs));

        List<String> actual = aclService.validateSelfAssignedAdmin(aceTopicPrefixedOwnerTargetNsToTargetNs, namespace);
        assertEquals(2, actual.size());

        actual = aclService.validateSelfAssignedAdmin(aceTopicPrefixedOwnerTargetNsToTargetNs2, namespace);
        assertEquals(1, actual.size());
    }

    @ParameterizedTest
    @CsvSource({
        "project1,project2_t1,project1,project2_t1",
        "project1.,project2_t1,project1_,project2.t1",
        "project1,project2_t1,project1_sub,project1_t1",
        "project1.,project2_t1,project1_sub,project1_t1"
    })
    void shouldValidateFailAsAdminWhenAclOverlapAsChild(
            String existingA, String existingB, String toCreateA, String toCreateB) {
        // Another namespace is already OWNER of PREFIXED or LITERAL resource.
        // Example :
        // If already exists:
        //   namespace1 OWNER:PREFIXED:project1
        //   namespace1 OWNER:LITERAL:project2_t1
        // And we try to create:
        //   namespace2 OWNER:PREFIXED:project1             KO 1 same          <<<<<<
        //   namespace2 OWNER:LITERAL:project1              KO 2 same          <<<<<<
        //   namespace2 OWNER:PREFIXED:project1_sub         KO 3 child overlap
        //   namespace2 OWNER:LITERAL:project1_t1           KO 4 child overlap
        //   namespace2 OWNER:PREFIXED:proj                 KO 5 parent overlap
        //   namespace2 OWNER:PREFIXED:project2             KO 6 parent overlap
        //
        //   namespace2 OWNER:PREFIXED:project3_topic1_sub  OK 7
        //   namespace2 OWNER:LITERAL:project2              OK 8
        //   namespace2 OWNER:LITERAL:proj                  OK 9

        AccessControlEntry aceTopicPrefixedOwnerOtherNsToOtherNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-existing1")
                        .namespace("other-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource(existingA)
                        .grantedTo("other-ns")
                        .build())
                .build();

        AccessControlEntry aceTopicLiteralOwnerOtherNsToOtherNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-existing2")
                        .namespace("other-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource(existingB)
                        .grantedTo("other-ns")
                        .build())
                .build();

        Namespace namespace = Namespace.builder()
                .metadata(Metadata.builder().name("target-ns").cluster("local").build())
                .build();

        AccessControlEntry aceTopicPrefixedOwnerTargetNsToTargetNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-tocreate")
                        .namespace("target-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource(toCreateA)
                        .grantedTo("target-ns")
                        .build())
                .build();

        AccessControlEntry aceTopicLiteralOwnerTargetNsToTargetNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-tocreate")
                        .namespace("target-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource(toCreateB)
                        .grantedTo("target-ns")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(aceTopicPrefixedOwnerOtherNsToOtherNs, aceTopicLiteralOwnerOtherNsToOtherNs));

        List<String> actual = aclService.validateSelfAssignedAdmin(aceTopicPrefixedOwnerTargetNsToTargetNs, namespace);
        assertEquals(1, actual.size());

        actual = aclService.validateSelfAssignedAdmin(aceTopicLiteralOwnerTargetNsToTargetNs, namespace);
        assertEquals(1, actual.size());
    }

    @Test
    void shouldValidateAclsAsAdmin() {
        // Another namespace is already OWNER of PREFIXED or LITERAL resource
        // Example :
        // If already exists:
        //   namespace1 OWNER:PREFIXED:project1
        //   namespace1 OWNER:LITERAL:project2_t1
        //   namespace1 OWNER:PREFIXED:p of CONNECT (should not interfere)
        //   namespace1 READ:PREFIXED:p OF TOPIC (should not interfere)
        // And we try to create:
        //   namespace2 OWNER:PREFIXED:project1             KO 1 same
        //   namespace2 OWNER:LITERAL:project1              KO 2 same
        //   namespace2 OWNER:PREFIXED:project1_sub         KO 3 child overlap
        //   namespace2 OWNER:LITERAL:project1_t1           KO 4 child overlap
        //   namespace2 OWNER:PREFIXED:proj                 KO 5 parent overlap
        //   namespace2 OWNER:PREFIXED:project2             KO 6 parent overlap
        //
        //   namespace2 OWNER:PREFIXED:project3_topic1_sub  OK 7   <<<<<<<<
        //   namespace2 OWNER:LITERAL:project2              OK 8   <<<<<<<<
        //   namespace2 OWNER:LITERAL:proj                  OK 9   <<<<<<<<

        AccessControlEntry aceTopicPrefixedOwnerOtherNsToOtherNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-existing1")
                        .namespace("other-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("project1")
                        .grantedTo("other-ns")
                        .build())
                .build();

        AccessControlEntry aceTopicLiteralOwnerOtherNsToOtherNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-existing2")
                        .namespace("other-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("project2_t1")
                        .grantedTo("other-ns")
                        .build())
                .build();

        AccessControlEntry aceConnectPrefixedOwnerOtherNsToOtherNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-existing2")
                        .namespace("other-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("p")
                        .grantedTo("other-ns")
                        .build())
                .build();

        Namespace namespace = Namespace.builder()
                .metadata(Metadata.builder().name("target-ns").cluster("local").build())
                .build();

        AccessControlEntry aceTopicPrefixedOwnerTargetNsToTargetNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-tocreate")
                        .namespace("target-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("project3_topic1_sub")
                        .grantedTo("target-ns")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(
                        aceTopicPrefixedOwnerOtherNsToOtherNs,
                        aceTopicLiteralOwnerOtherNsToOtherNs,
                        aceConnectPrefixedOwnerOtherNsToOtherNs));

        List<String> actual = aclService.validateSelfAssignedAdmin(aceTopicPrefixedOwnerTargetNsToTargetNs, namespace);
        assertTrue(actual.isEmpty());

        AccessControlEntry toCreate2 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-tocreate")
                        .namespace("target-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("project2")
                        .grantedTo("target-ns")
                        .build())
                .build();

        actual = aclService.validateSelfAssignedAdmin(toCreate2, namespace);
        assertTrue(actual.isEmpty());

        AccessControlEntry aceTopicLiteralOwnerTargetNsToTargetNs = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-tocreate")
                        .namespace("target-ns")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("proj")
                        .grantedTo("target-ns")
                        .build())
                .build();

        actual = aclService.validateSelfAssignedAdmin(aceTopicLiteralOwnerTargetNsToTargetNs, namespace);
        assertTrue(actual.isEmpty());
    }

    @Test
    void shouldFindAllAclsGrantedToNamespace() {
        Namespace namespace = Namespace.builder()
                .metadata(
                        Metadata.builder().name("namespace1").cluster("cluster").build())
                .build();

        AccessControlEntry ace1 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        AccessControlEntry ace2 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        AccessControlEntry ace3 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace2")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        AccessControlEntry ace4 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("*")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(ace1, ace2, ace3, ace4));

        List<AccessControlEntry> actual = aclService.findAllGrantedToNamespace(namespace);
        assertEquals(3, actual.size());
    }

    @Test
    void shouldFindAllAclsGrantedToAll() {
        AccessControlEntry ace1 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry ace2 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry ace3 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry ace4 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("*")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(ace1, ace2, ace3, ace4));

        List<AccessControlEntry> actual = aclService.findAllPublicGrantedTo();
        assertEquals(1, actual.size());
    }

    @Test
    void shouldFindAllAclForNamespace() {
        Namespace ns = Namespace.builder()
                .metadata(Metadata.builder().name("namespace1").cluster("local").build())
                .build();

        AccessControlEntry ace1 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry ace2 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry ace3 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .namespace("namespace2")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace2")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(ace1, ace2, ace3));

        List<AccessControlEntry> actual = aclService.findAllForNamespace(ns);
        assertEquals(2, actual.size());
    }

    @Test
    void shouldFindAllAcls() {
        AccessControlEntry ace1 = AccessControlEntry.builder()
                .metadata(Metadata.builder().namespace("namespace1").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry ace2 = AccessControlEntry.builder()
                .metadata(Metadata.builder().namespace("namespace2").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry ace3 = AccessControlEntry.builder()
                .metadata(Metadata.builder().namespace("namespace3").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace3")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(ace1, ace2, ace3));

        List<AccessControlEntry> actual = aclService.findAll();
        assertEquals(3, actual.size());
    }

    @Test
    void shouldCheckIfNamespaceIsOwnerOfResource() {
        AccessControlEntry aceTopicPrefixedOwner = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("main")
                        .grantedTo("namespace")
                        .build())
                .build();

        AccessControlEntry aceConnectLiteralOwner = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("connect")
                        .grantedTo("namespace")
                        .build())
                .build();

        AccessControlEntry aceConnectLiteralWrite = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.WRITE)
                        .resource("connect")
                        .grantedTo("namespace-other")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll())
                .thenReturn(List.of(aceTopicPrefixedOwner, aceConnectLiteralOwner, aceConnectLiteralWrite));

        assertTrue(
                aclService.isNamespaceOwnerOfResource("namespace", AccessControlEntry.ResourceType.CONNECT, "connect"));

        assertTrue(aclService.isNamespaceOwnerOfResource("namespace", AccessControlEntry.ResourceType.TOPIC, "main"));

        assertTrue(
                aclService.isNamespaceOwnerOfResource("namespace", AccessControlEntry.ResourceType.TOPIC, "main.sub"),
                "subresource");

        assertFalse(aclService.isNamespaceOwnerOfResource(
                "namespace-other", AccessControlEntry.ResourceType.TOPIC, "main"));

        assertFalse(aclService.isNamespaceOwnerOfResource(
                "namespace-other", AccessControlEntry.ResourceType.CONNECT, "connect"));
    }

    @Test
    void shouldNotCollideIfDifferentResource() {
        AccessControlEntry aceTopicPrefixedOwner = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("abc.")
                        .grantedTo("namespace")
                        .build())
                .build();

        AccessControlEntry aceConnectLiteralOwner = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("abc_")
                        .grantedTo("namespace")
                        .build())
                .build();

        assertFalse(aclService.topicAclsCollideWithParentOrChild(aceTopicPrefixedOwner, aceConnectLiteralOwner));
        assertFalse(aclService.topicAclsCollideWithParentOrChild(aceConnectLiteralOwner, aceTopicPrefixedOwner));
        assertFalse(aclService.topicAclsCollide(aceTopicPrefixedOwner, aceConnectLiteralOwner));
        assertFalse(aclService.topicAclsCollide(aceConnectLiteralOwner, aceTopicPrefixedOwner));
    }

    @Test
    void shouldFindAclGrantedToNamespaceByWildcardName() {
        Namespace ns = Namespace.builder()
                .metadata(
                        Metadata.builder().name("namespace2").cluster("cluster").build())
                .build();

        AccessControlEntry acl1 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-acl-topic")
                        .namespace("namespace1")
                        .cluster("cluster")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-ns1-read-to-ns2")
                        .namespace("namespace1")
                        .cluster("cluster")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl3 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-connect-write-to-ns2")
                        .namespace("namespace1")
                        .cluster("cluster")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .permission(AccessControlEntry.Permission.WRITE)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl4 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns2-acl-topic")
                        .namespace("namespace2")
                        .cluster("cluster")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl5 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns3-read-topic-all")
                        .namespace("namespace3")
                        .cluster("cluster")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("*")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(acl1, acl2, acl3, acl4, acl5));

        assertEquals(List.of(acl2, acl3, acl4, acl5), aclService.findAllGrantedToNamespaceByWildcardName(ns, "*"));
        assertEquals(List.of(acl2), aclService.findAllGrantedToNamespaceByWildcardName(ns, "acl-ns1-read-to-ns2"));
        assertEquals(List.of(acl2, acl5), aclService.findAllGrantedToNamespaceByWildcardName(ns, "*read*"));
        assertTrue(aclService
                .findAllGrantedToNamespaceByWildcardName(ns, "not-found")
                .isEmpty());
    }

    @Test
    void shouldFindAclGrantedByNamespaceByWildcardName() {
        Namespace ns = Namespace.builder()
                .metadata(Metadata.builder().name("namespace1").cluster("local").build())
                .build();

        AccessControlEntry acl1 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-acl-topic")
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-read-ns2")
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl3 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-connect-write-to-ns2")
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .permission(AccessControlEntry.Permission.WRITE)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl4 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns2-acl-topic")
                        .namespace("namespace2")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl5 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns3-read-topic-all")
                        .namespace("namespace3")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("*")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(acl1, acl2, acl3, acl4, acl5));

        assertEquals(List.of(acl1, acl2, acl3), aclService.findAllGrantedByNamespaceByWildcardName(ns, "*"));
        assertEquals(List.of(acl2), aclService.findAllGrantedByNamespaceByWildcardName(ns, "ns1-read-ns2"));
        assertEquals(List.of(acl3), aclService.findAllGrantedByNamespaceByWildcardName(ns, "*-to-ns2"));
        assertTrue(aclService
                .findAllGrantedByNamespaceByWildcardName(ns, "not-found")
                .isEmpty());
    }

    @Test
    void shouldFindAclGrantedByNamespaceToOthersByWildcardName() {
        Namespace ns = Namespace.builder()
                .metadata(Metadata.builder().name("namespace1").cluster("local").build())
                .build();

        AccessControlEntry acl1 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-acl-topic")
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-read-ns2")
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl3 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-connect-write-to-ns2")
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .permission(AccessControlEntry.Permission.WRITE)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl4 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns2-acl-topic")
                        .namespace("namespace2")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl5 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns3-read-topic-all")
                        .namespace("namespace3")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("*")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(acl1, acl2, acl3, acl4, acl5));

        assertEquals(List.of(acl2, acl3), aclService.findAllGrantedByNamespaceToOthersByWildcardName(ns, "*"));
        assertEquals(List.of(acl2), aclService.findAllGrantedByNamespaceToOthersByWildcardName(ns, "ns1-read-ns2"));
        assertEquals(List.of(acl3), aclService.findAllGrantedByNamespaceToOthersByWildcardName(ns, "*-to-ns2"));
        assertTrue(aclService
                .findAllGrantedByNamespaceToOthersByWildcardName(ns, "not-found")
                .isEmpty());
    }

    @Test
    void shouldFindAclRelatedToNamespaceByWildcardName() {
        AccessControlEntry acl1 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-acl-topic")
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("acl-ns2-read-to-ns1")
                        .namespace("namespace2")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl3 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns1-acl-connect")
                        .namespace("namespace1")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl4 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns2-acl-topic")
                        .namespace("namespace2")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl5 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns3-read-topic-all")
                        .namespace("namespace3")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("*")
                        .build())
                .build();

        AccessControlEntry acl6 = AccessControlEntry.builder()
                .metadata(Metadata.builder()
                        .name("ns3-write-acl-ns1")
                        .namespace("namespace3")
                        .cluster("local")
                        .build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.GROUP)
                        .permission(AccessControlEntry.Permission.WRITE)
                        .grantedTo("namespace1")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(acl1, acl2, acl3, acl4, acl5, acl6));

        Namespace ns1 = Namespace.builder()
                .metadata(Metadata.builder().name("namespace1").cluster("local").build())
                .build();

        assertEquals(
                List.of(acl1, acl2, acl3, acl5, acl6), aclService.findAllRelatedToNamespaceByWildcardName(ns1, "*"));
        assertEquals(List.of(acl1, acl5), aclService.findAllRelatedToNamespaceByWildcardName(ns1, "*topic*"));
        assertEquals(List.of(acl1, acl3), aclService.findAllRelatedToNamespaceByWildcardName(ns1, "ns1-acl*"));
        assertTrue(aclService
                .findAllRelatedToNamespaceByWildcardName(ns1, "not-found")
                .isEmpty());

        Namespace ns2 = Namespace.builder()
                .metadata(Metadata.builder().name("namespace2").cluster("local").build())
                .build();

        assertEquals(List.of(acl2, acl4, acl5), aclService.findAllRelatedToNamespaceByWildcardName(ns2, "*"));
        assertEquals(List.of(acl2, acl4), aclService.findAllRelatedToNamespaceByWildcardName(ns2, "*ns2*"));

        Namespace ns3 = Namespace.builder()
                .metadata(Metadata.builder().name("namespace3").cluster("local").build())
                .build();

        assertEquals(List.of(acl5, acl6), aclService.findAllRelatedToNamespaceByWildcardName(ns3, "*"));
        assertEquals(List.of(acl6), aclService.findAllRelatedToNamespaceByWildcardName(ns3, "ns3-write-acl-ns1"));
    }

    @Test
    void shouldFindResourceWhereGivenNamespaceIsOwnerOf() {
        Namespace ns = Namespace.builder()
                .metadata(Metadata.builder().name("namespace1").cluster("local").build())
                .build();
        AccessControlEntry acl1 = AccessControlEntry.builder()
                .metadata(Metadata.builder().cluster("local").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .metadata(Metadata.builder().cluster("local").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl3 = AccessControlEntry.builder()
                .metadata(Metadata.builder().cluster("local").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace1")
                        .build())
                .build();

        AccessControlEntry acl4 = AccessControlEntry.builder()
                .metadata(Metadata.builder().cluster("local").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .grantedTo("namespace2")
                        .build())
                .build();

        AccessControlEntry acl5 = AccessControlEntry.builder()
                .metadata(Metadata.builder().cluster("local").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .permission(AccessControlEntry.Permission.READ)
                        .grantedTo("*")
                        .build())
                .build();

        AccessControlEntry acl6 = AccessControlEntry.builder()
                .metadata(Metadata.builder().cluster("local").build())
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.GROUP)
                        .permission(AccessControlEntry.Permission.WRITE)
                        .grantedTo("namespace1")
                        .build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(acl1, acl2, acl3, acl4, acl5, acl6));

        assertEquals(
                List.of(acl1),
                aclService.findResourceOwnerGrantedToNamespace(ns, AccessControlEntry.ResourceType.TOPIC));
        assertEquals(
                List.of(acl3),
                aclService.findResourceOwnerGrantedToNamespace(ns, AccessControlEntry.ResourceType.CONNECT));
        assertEquals(
                List.of(), aclService.findResourceOwnerGrantedToNamespace(ns, AccessControlEntry.ResourceType.GROUP));
    }

    @Test
    void shouldPrefixedAclsMatchResource() {
        AccessControlEntry acl1 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("abc.")
                        .grantedTo("namespace")
                        .build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.PREFIXED)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("abc_")
                        .grantedTo("namespace")
                        .build())
                .build();
        List<AccessControlEntry> acls = List.of(acl1, acl2);

        assertFalse(aclService.isResourceCoveredByAcls(acls, "xyz.topic1"));
        assertFalse(aclService.isResourceCoveredByAcls(acls, "topic1-abc"));
        assertFalse(aclService.isResourceCoveredByAcls(acls, "abc-topic1"));
        assertTrue(aclService.isResourceCoveredByAcls(acls, "abc.topic1"));
        assertTrue(aclService.isResourceCoveredByAcls(acls, "abc_topic1"));
    }

    @Test
    void shouldLiteralAclsMatchResource() {
        AccessControlEntry acl1 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.TOPIC)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("abc.topic1")
                        .grantedTo("namespace")
                        .build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .resourceType(AccessControlEntry.ResourceType.CONNECT)
                        .resourcePatternType(AccessControlEntry.ResourcePatternType.LITERAL)
                        .permission(AccessControlEntry.Permission.OWNER)
                        .resource("abc-topic1")
                        .grantedTo("namespace")
                        .build())
                .build();
        List<AccessControlEntry> acls = List.of(acl1, acl2);

        assertFalse(aclService.isResourceCoveredByAcls(acls, "xyz.topic1"));
        assertFalse(aclService.isResourceCoveredByAcls(acls, "abc.topic12"));
        assertFalse(aclService.isResourceCoveredByAcls(acls, "abc_topic1"));
        assertTrue(aclService.isResourceCoveredByAcls(acls, "abc.topic1"));
        assertTrue(aclService.isResourceCoveredByAcls(acls, "abc-topic1"));
    }

    @Test
    void shouldDeleteAllGrantedToAclsForNamespace() {
        AccessControlEntry acl1 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        AccessControlEntry acl3 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace2")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(acl1, acl2, acl3));
        when(applicationContext.getBean(AccessControlEntryAsyncExecutor.class, Qualifiers.byName("cluster")))
                .thenReturn(accessControlEntryAsyncExecutor);
        doNothing().when(accessControlEntryRepository).delete(any());

        Namespace namespace = Namespace.builder()
                .metadata(
                        Metadata.builder().name("namespace1").cluster("cluster").build())
                .build();

        aclService.deleteAllGrantedToNamespace(namespace);

        verify(accessControlEntryRepository, times(2)).delete(argThat(arg -> arg.equals(acl1) || arg.equals(acl2)));
        verify(accessControlEntryRepository, never()).delete(acl3);
    }

    @Test
    void shouldDeleteAllGrantedToAclsForNamespaceAndNotDeletePublicAcl() {
        AccessControlEntry acl1 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        AccessControlEntry acl2 = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("namespace1")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        AccessControlEntry publicAcl = AccessControlEntry.builder()
                .spec(AccessControlEntry.AccessControlEntrySpec.builder()
                        .grantedTo("*")
                        .build())
                .metadata(Metadata.builder().cluster("cluster").build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of(acl1, acl2, publicAcl));
        when(applicationContext.getBean(AccessControlEntryAsyncExecutor.class, Qualifiers.byName("cluster")))
                .thenReturn(accessControlEntryAsyncExecutor);
        doNothing().when(accessControlEntryRepository).delete(any());

        Namespace namespace = Namespace.builder()
                .metadata(
                        Metadata.builder().name("namespace1").cluster("cluster").build())
                .build();

        aclService.deleteAllGrantedToNamespace(namespace);

        verify(accessControlEntryRepository, times(2)).delete(argThat(arg -> arg.equals(acl1) || arg.equals(acl2)));
        verify(accessControlEntryRepository, never()).delete(publicAcl);
    }

    @Test
    void shouldNotDeleteAllAclsForNamespaceWhenNoAcl() {
        Namespace namespace = Namespace.builder()
                .metadata(
                        Metadata.builder().name("namespace1").cluster("cluster").build())
                .build();

        when(accessControlEntryRepository.findAll()).thenReturn(List.of());

        aclService.deleteAllGrantedToNamespace(namespace);

        verify(accessControlEntryRepository, never()).delete(any());
    }
}
