package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 規劃工具使用的封閉 semantic identity，轉換後才進入 provider HTTP contract */
public final class SemanticPlanningIdentities {

    private SemanticPlanningIdentities() {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Concept.Type.class, name = "TYPE"),
            @JsonSubTypes.Type(value = Concept.Method.class, name = "METHOD"),
            @JsonSubTypes.Type(value = Concept.Field.class, name = "FIELD"),
            @JsonSubTypes.Type(value = Concept.AnnotationUsage.class, name = "ANNOTATION_USAGE"),
            @JsonSubTypes.Type(value = Concept.TypeUsage.class, name = "TYPE_USAGE"),
            @JsonSubTypes.Type(value = Concept.ApiRoute.class, name = "API_ROUTE"),
            @JsonSubTypes.Type(value = Concept.MqDestination.class, name = "MQ_DESTINATION"),
            @JsonSubTypes.Type(value = Concept.Schedule.class, name = "SCHEDULE"),
            @JsonSubTypes.Type(value = Concept.MapperStatement.class, name = "MAPPER_STATEMENT"),
            @JsonSubTypes.Type(value = Concept.MapperStatementVariant.class, name = "MAPPER_STATEMENT_VARIANT")
    })
    public sealed interface Concept permits Concept.Type, Concept.Method, Concept.Field, Concept.AnnotationUsage,
            Concept.TypeUsage, Concept.ApiRoute, Concept.MqDestination, Concept.Schedule, Concept.MapperStatement,
            Concept.MapperStatementVariant {

        String kind();

        record Type(@JsonProperty(required = true) String kind,
                    @JsonProperty(required = true) @NotNull @Valid SemanticDtos.SourceTypeIdentityPayload sourceType)
                implements Concept {
            public Type { kind = exact(kind, "TYPE"); sourceType = Objects.requireNonNull(sourceType, "sourceType is required"); }
        }

        record Method(@JsonProperty(required = true) String kind,
                      @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MethodTargetPayload target)
                implements Concept {
            public Method { kind = exact(kind, "METHOD"); target = Objects.requireNonNull(target, "target is required"); }
        }

        record Field(@JsonProperty(required = true) String kind,
                     @JsonProperty(required = true) @NotNull @Valid SemanticDtos.SourceMemberIdentityPayload identity)
                implements Concept {
            public Field {
                kind = exact(kind, "FIELD");
                identity = Objects.requireNonNull(identity, "identity is required");
            }
        }

        record AnnotationUsage(@JsonProperty(required = true) String kind,
                               @JsonProperty(required = true) @NotNull @Valid SemanticDtos.DeclarationSubjectPayload declaration,
                               @JsonProperty(required = true) @NotNull @Valid SemanticDtos.AnnotationTypePayload annotationType)
                implements Concept {
            public AnnotationUsage {
                kind = exact(kind, "ANNOTATION_USAGE");
                declaration = Objects.requireNonNull(declaration, "declaration is required");
                annotationType = Objects.requireNonNull(annotationType, "annotationType is required");
            }
        }

        record TypeUsage(@JsonProperty(required = true) String kind,
                         @JsonProperty(required = true) @NotNull @Valid SemanticDtos.DeclarationSubjectPayload owner,
                         @JsonProperty(required = true) @NotNull @Valid SemanticDtos.TypeUsageLocationPayload location,
                         @JsonProperty(required = true) @NotNull @Valid List<SemanticDtos.TypeUsagePathPayload> path,
                         @JsonProperty(required = true) @NotNull @Valid SemanticDtos.ReferencedTypePayload referencedType)
                implements Concept {
            public TypeUsage {
                kind = exact(kind, "TYPE_USAGE");
                owner = Objects.requireNonNull(owner, "owner is required");
                location = Objects.requireNonNull(location, "location is required");
                path = List.copyOf(Objects.requireNonNull(path, "path is required"));
                referencedType = Objects.requireNonNull(referencedType, "referencedType is required");
            }
        }

        record ApiRoute(@JsonProperty(required = true) String kind,
                        @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MethodTargetPayload target,
                        @JsonProperty(required = true) @NotBlank String httpVerb,
                        @JsonProperty(required = true) @NotBlank String route)
                implements Concept {
            public ApiRoute {
                kind = exact(kind, "API_ROUTE");
                target = Objects.requireNonNull(target, "target is required");
                httpVerb = requiredText(httpVerb, "httpVerb"); route = requiredText(route, "route");
            }
        }

        record MqDestination(@JsonProperty(required = true) String kind,
                             @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MethodTargetPayload target,
                             @JsonProperty(required = true) @NotBlank String broker,
                             @JsonProperty(required = true) @NotBlank String destination)
                implements Concept {
            public MqDestination {
                kind = exact(kind, "MQ_DESTINATION"); target = Objects.requireNonNull(target, "target is required");
                broker = requiredText(broker, "broker"); destination = requiredText(destination, "destination");
            }
        }

        record Schedule(@JsonProperty(required = true) String kind,
                        @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MethodTargetPayload target,
                        @JsonProperty(required = true) @NotBlank String triggerKind,
                        Optional<@NotBlank String> triggerValue)
                implements Concept {
            public Schedule {
                kind = exact(kind, "SCHEDULE"); target = Objects.requireNonNull(target, "target is required");
                triggerKind = requiredText(triggerKind, "triggerKind");
                triggerValue = Optional.ofNullable(triggerValue).orElse(Optional.empty());
            }
        }

        record MapperStatement(@JsonProperty(required = true) String kind,
                               @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MapperStatementKeyPayload identity)
                implements Concept {
            public MapperStatement { kind = exact(kind, "MAPPER_STATEMENT"); identity = Objects.requireNonNull(identity, "identity is required"); }
        }

        record MapperStatementVariant(@JsonProperty(required = true) String kind,
                                      @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MapperStatementIdentityPayload identity)
                implements Concept {
            public MapperStatementVariant { kind = exact(kind, "MAPPER_STATEMENT_VARIANT"); identity = Objects.requireNonNull(identity, "identity is required"); }
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Evidence.AnnotationSql.class, name = "ANNOTATION_SQL"),
            @JsonSubTypes.Type(value = Evidence.MapperStatement.class, name = "MAPPER_STATEMENT"),
            @JsonSubTypes.Type(value = Evidence.MapperFragment.class, name = "MAPPER_FRAGMENT")
    })
    public sealed interface Evidence permits Evidence.AnnotationSql, Evidence.MapperStatement, Evidence.MapperFragment {
        String kind();

        record AnnotationSql(@JsonProperty(required = true) String kind,
                             @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MapperStatementIdentityPayload statementIdentity)
                implements Evidence {
            public AnnotationSql { kind = exact(kind, "ANNOTATION_SQL"); statementIdentity = Objects.requireNonNull(statementIdentity, "statementIdentity is required"); }
        }

        record MapperStatement(@JsonProperty(required = true) String kind,
                               @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MapperStatementIdentityPayload statementIdentity)
                implements Evidence {
            public MapperStatement { kind = exact(kind, "MAPPER_STATEMENT"); statementIdentity = Objects.requireNonNull(statementIdentity, "statementIdentity is required"); }
        }

        record MapperFragment(@JsonProperty(required = true) String kind,
                              @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MapperFragmentIdentityPayload fragmentIdentity)
                implements Evidence {
            public MapperFragment { kind = exact(kind, "MAPPER_FRAGMENT"); fragmentIdentity = Objects.requireNonNull(fragmentIdentity, "fragmentIdentity is required"); }
        }
    }

    public static SemanticDtos.ConceptFollowUpIdentity toWire(Concept identity) {
        Objects.requireNonNull(identity, "concept identity is required");
        return switch (identity) {
            case Concept.Type value -> concept(value.kind(), Optional.of(value.sourceType()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case Concept.Method value -> concept(value.kind(), Optional.empty(), Optional.of(value.target()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case Concept.Field value -> concept(value.kind(), Optional.empty(), Optional.empty(), Optional.of(sourceMemberIdentity(value.identity())), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case Concept.AnnotationUsage value -> concept(value.kind(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(value.declaration()), Optional.of(value.annotationType()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case Concept.TypeUsage value -> concept(value.kind(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(value.owner()), Optional.of(value.location()), Optional.of(value.path()), Optional.of(value.referencedType()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case Concept.ApiRoute value -> concept(value.kind(), Optional.empty(), Optional.of(value.target()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(value.httpVerb()), Optional.of(value.route()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case Concept.MqDestination value -> concept(value.kind(), Optional.empty(), Optional.of(value.target()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(value.broker()), Optional.of(value.destination()), Optional.empty(), Optional.empty());
            case Concept.Schedule value -> concept(value.kind(), Optional.empty(), Optional.of(value.target()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(value.triggerKind()), value.triggerValue());
            case Concept.MapperStatement value -> concept(value.kind(), Optional.empty(), Optional.empty(), Optional.of(value.identity()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            case Concept.MapperStatementVariant value -> concept(value.kind(), Optional.empty(), Optional.empty(), Optional.of(value.identity()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        };
    }

    public static SemanticDtos.EvidenceSourceFollowUpIdentity toWire(Evidence identity) {
        Objects.requireNonNull(identity, "evidence identity is required");
        return switch (identity) {
            case Evidence.AnnotationSql value -> new SemanticDtos.EvidenceSourceFollowUpIdentity(value.kind(), Optional.of(value.statementIdentity()), Optional.empty());
            case Evidence.MapperStatement value -> new SemanticDtos.EvidenceSourceFollowUpIdentity(value.kind(), Optional.of(value.statementIdentity()), Optional.empty());
            case Evidence.MapperFragment value -> new SemanticDtos.EvidenceSourceFollowUpIdentity(value.kind(), Optional.empty(), Optional.of(value.fragmentIdentity()));
        };
    }

    private static SemanticDtos.ConceptFollowUpIdentity concept(
            String kind, Optional<SemanticDtos.SourceTypeIdentityPayload> sourceType,
            Optional<SemanticDtos.MethodTargetPayload> target, Optional<SemanticDtos.ConceptIdentityTargetPayload> identity,
            Optional<SemanticDtos.DeclarationSubjectPayload> declaration, Optional<SemanticDtos.AnnotationTypePayload> annotationType,
            Optional<SemanticDtos.DeclarationSubjectPayload> owner, Optional<SemanticDtos.TypeUsageLocationPayload> location,
            Optional<List<SemanticDtos.TypeUsagePathPayload>> path, Optional<SemanticDtos.ReferencedTypePayload> referencedType,
            Optional<String> httpVerb, Optional<String> route, Optional<String> broker, Optional<String> destination,
            Optional<String> triggerKind, Optional<String> triggerValue) {
        return new SemanticDtos.ConceptFollowUpIdentity(kind, sourceType, target, identity, declaration, annotationType,
                owner, location, path, referencedType, httpVerb, route, broker, destination, triggerKind, triggerValue);
    }

    private static SemanticDtos.ConceptIdentityTargetPayload sourceMemberIdentity(
            SemanticDtos.SourceMemberIdentityPayload identity) {
        return switch (identity) {
            case SemanticDtos.SourceMemberIdentityPayload.TypeMember typeMember -> typeMember;
            case SemanticDtos.SourceMemberIdentityPayload.MethodScoped methodScoped -> methodScoped;
        };
    }

    private static String exact(String value, String expected) {
        String required = Objects.requireNonNull(value, "kind is required");
        if (!expected.equals(required)) {
            throw new IllegalArgumentException("unexpected discriminator");
        }
        return required;
    }

    private static String requiredText(String value, String name) {
        String required = Objects.requireNonNull(value, name + " is required");
        if (required.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return required;
    }
}
