/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.jsonschema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node.NonNumericFloat;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.BigDecimalShape;
import software.amazon.smithy.model.shapes.BigIntegerShape;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ByteShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.LongShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.DefaultTrait;
import software.amazon.smithy.model.traits.DeprecatedTrait;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.LengthTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.PatternTrait;
import software.amazon.smithy.model.traits.RangeTrait;
import software.amazon.smithy.model.traits.TitleTrait;
import software.amazon.smithy.model.traits.UniqueItemsTrait;
import software.amazon.smithy.utils.ListUtils;

final class JsonSchemaShapeVisitor extends ShapeVisitor.Default<Schema> {
    private static final Set<String> NON_NUMERIC_FLOAT_VALUES = NonNumericFloat.stringRepresentations();

    private final Model model;
    private final JsonSchemaConverter converter;
    private final List<JsonSchemaMapper> mappers;

    JsonSchemaShapeVisitor(Model model, JsonSchemaConverter converter, List<JsonSchemaMapper> mappers) {
        this.model = model;
        this.converter = converter;
        this.mappers = mappers;
    }

    @Override
    public Schema getDefault(Shape shape) {
        throw new SmithyJsonSchemaException("Unable to convert " + shape + " to JSON Schema");
    }

    @Override
    public Schema documentShape(DocumentShape shape) {
        // A document type becomes just `{}`
        return buildSchema(shape, createBuilder(shape, null));
    }

    @Override
    public Schema blobShape(BlobShape shape) {
        return buildSchema(shape, createBuilder(shape, "string"));
    }

    @Override
    public Schema booleanShape(BooleanShape shape) {
        return buildSchema(shape, createBuilder(shape, "boolean"));
    }

    @Override
    public Schema listShape(ListShape shape) {
        Schema.Builder builder = createBuilder(shape, "array").items(createRef(shape.getMember()));
        if (shape.hasTrait(UniqueItemsTrait.ID)) {
            builder.uniqueItems(true);
        }
        return buildSchema(shape, builder);
    }

    private Schema createRef(MemberShape member) {
        if (converter.isInlined(member)) {
            return member.accept(this);
        } else {
            Schema.Builder refBuilder = Schema.builder().ref(converter.toPointer(member.getTarget()));
            if (member.hasTrait(DeprecatedTrait.ID) && getJsonSchemaVersion() != JsonSchemaVersion.DRAFT07) {
                refBuilder.deprecated(true);
            }

            if (converter.getConfig().getAddReferenceDescriptions()) {
                descriptionMessage(member).ifPresent(refBuilder::description);
            }

            // Wrap the ref and default in an allOf if disableDefaultValues has been not been disabled on config.
            if (member.hasTrait(DefaultTrait.ID) && !converter.getConfig().getDisableDefaultValues()) {
                Schema def = Schema.builder().defaultValue(member.expectTrait(DefaultTrait.class).toNode()).build();
                return Schema.builder().allOf(ListUtils.of(refBuilder.build(), def)).build();
            }
            return refBuilder.build();
        }
    }

    @Override
    public Schema mapShape(MapShape shape) {
        JsonSchemaConfig.MapStrategy mapStrategy = converter.getConfig().getMapStrategy();

        switch (mapStrategy) {
            case PROPERTY_NAMES:
                return buildSchema(shape,
                        createBuilder(shape, "object")
                                .propertyNames(createRef(shape.getKey()))
                                .additionalProperties(createRef(shape.getValue())));
            case PATTERN_PROPERTIES:
                String keyPattern = shape.getKey()
                        .getMemberTrait(model, PatternTrait.class)
                        .map(PatternTrait::getPattern)
                        .map(Pattern::pattern)
                        .orElse(".+");
                return buildSchema(shape,
                        createBuilder(shape, "object")
                                .putPatternProperty(keyPattern, createRef(shape.getValue())));
            default:
                throw new SmithyJsonSchemaException(String.format("Unsupported map strategy: %s", mapStrategy));
        }
    }

    @Override
    public Schema byteShape(ByteShape shape) {
        return buildIntegerSchema(shape);
    }

    @Override
    public Schema shortShape(ShortShape shape) {
        return buildIntegerSchema(shape);
    }

    @Override
    public Schema integerShape(IntegerShape shape) {
        return buildIntegerSchema(shape);
    }

    @Override
    public Schema longShape(LongShape shape) {
        return buildIntegerSchema(shape);
    }

    private Schema buildIntegerSchema(Shape shape) {
        String type = converter.getConfig().getUseIntegerType() ? "integer" : "number";
        return buildSchema(shape, createBuilder(shape, type));
    }

    @Override
    public Schema floatShape(FloatShape shape) {
        return buildFloatSchema(shape);
    }

    @Override
    public Schema doubleShape(DoubleShape shape) {
        return buildFloatSchema(shape);
    }

    private Schema buildFloatSchema(Shape shape) {
        Schema.Builder numberBuilder = createBuilder(shape, "number");
        if (!converter.getConfig().getSupportNonNumericFloats()) {
            return buildSchema(shape, numberBuilder);
        }

        Schema nonNumericValues = Schema.builder()
                .type("string")
                .enumValues(NON_NUMERIC_FLOAT_VALUES)
                .build();

        Schema.Builder nonNumericNumberBuilder = createBuilder(shape, "number")
                .type(null)
                .oneOf(ListUtils.of(numberBuilder.build(), nonNumericValues));

        return buildSchema(shape, nonNumericNumberBuilder);
    }

    @Override
    public Schema bigIntegerShape(BigIntegerShape shape) {
        return buildSchema(shape, createBuilder(shape, "number"));
    }

    @Override
    public Schema bigDecimalShape(BigDecimalShape shape) {
        return buildSchema(shape, createBuilder(shape, "number"));
    }

    @Override
    public Schema stringShape(StringShape shape) {
        return buildSchema(shape, createBuilder(shape, "string"));
    }

    @Override
    public Schema structureShape(StructureShape shape) {
        return structuredShape(shape, shape.getAllMembers().values());
    }

    private Schema structuredShape(Shape container, Collection<MemberShape> memberShapes) {
        Schema.Builder builder = createBuilder(container, "object");

        List<String> required = new ArrayList<>();
        for (MemberShape member : memberShapes) {
            String memberName = converter.toPropertyName(member);
            if (member.isRequired()) {
                required.add(memberName);
            }
            builder.putProperty(memberName, createRef(member));
        }

        builder.required(required);
        return buildSchema(container, builder);
    }

    @Override
    public Schema unionShape(UnionShape shape) {
        JsonSchemaConfig.UnionStrategy unionStrategy = converter.getConfig().getUnionStrategy();

        switch (unionStrategy) {
            case OBJECT:
                return buildSchema(shape, createBuilder(shape, "object"));
            case STRUCTURE:
                return structuredShape(shape, shape.getAllMembers().values());
            case ONE_OF:
                List<Schema> schemas = new ArrayList<>();
                for (MemberShape member : shape.getAllMembers().values()) {
                    String memberName = converter.toPropertyName(member);
                    schemas.add(Schema.builder()
                            .type("object")
                            .title(memberName)
                            .required(ListUtils.of(memberName))
                            .putProperty(memberName, createRef(member))
                            .build());
                }
                return buildSchema(shape, createBuilder(shape, "object").type(null).oneOf(schemas));
            default:
                throw new SmithyJsonSchemaException(String.format("Unsupported union strategy: %s", unionStrategy));
        }
    }

    @Override
    public Schema enumShape(EnumShape shape) {
        JsonSchemaConfig.EnumStrategy enumStrategy = converter.getConfig().getEnumStrategy();

        switch (enumStrategy) {
            case ENUM:
                return super.enumShape(shape);
            case ONE_OF:
                Map<String, String> enumValues = shape.getEnumValues();
                List<Schema> schemas = new ArrayList<>();

                for (Map.Entry<String, MemberShape> entry : shape.getAllMembers().entrySet()) {
                    String memberName = entry.getKey();
                    MemberShape member = entry.getValue();
                    Schema enumSchema = Schema.builder()
                            .constValue(StringNode.from(enumValues.get(memberName)))
                            .description(member.getTrait(DocumentationTrait.class)
                                    .map(DocumentationTrait::getValue)
                                    .orElse(null))
                            .build();

                    schemas.add(enumSchema);
                }

                return buildSchema(shape,
                        Schema.builder()
                                .description(shape.getTrait(DocumentationTrait.class)
                                        .map(DocumentationTrait::getValue)
                                        .orElse(null))
                                .type("string")
                                .oneOf(schemas));
            default: {
                throw new SmithyJsonSchemaException(String.format("Unsupported enum strategy: %s", enumStrategy));
            }
        }
    }

    @Override
    public Schema timestampShape(TimestampShape shape) {
        return buildSchema(shape, createBuilder(shape, "string"));
    }

    @Override
    public Schema memberShape(MemberShape memberShape) {
        Shape target = getTarget(memberShape);
        return buildSchema(memberShape, updateBuilder(memberShape, target.accept(this).toBuilder()));
    }

    private Shape getTarget(MemberShape member) {
        return model.getShape(member.getTarget())
                .orElseThrow(() -> new SmithyJsonSchemaException("Unable to find the shape targeted by " + member));
    }

    private Schema.Builder createBuilder(Shape shape, String defaultType) {
        return updateBuilder(shape, Schema.builder().type(defaultType));
    }

    /**
     * Updates a schema builder using shared logic across shapes.
     *
     * @param shape Shape being converted.
     * @param builder Schema being built.
     * @return Returns the updated schema builder.
     */
    private Schema.Builder updateBuilder(Shape shape, Schema.Builder builder) {
        descriptionMessage(shape).ifPresent(builder::description);

        shape.getTrait(TitleTrait.class)
                .map(TitleTrait::getValue)
                .ifPresent(builder::title);

        shape.getTrait(MediaTypeTrait.class)
                .map(MediaTypeTrait::getValue)
                .ifPresent(builder::contentMediaType);

        shape.getMemberTrait(model, PatternTrait.class)
                .map(PatternTrait::getPattern)
                .map(Pattern::pattern)
                .ifPresent(builder::pattern);

        shape.getMemberTrait(model, RangeTrait.class).ifPresent(t -> {
            t.getMin().ifPresent(builder::minimum);
            t.getMax().ifPresent(builder::maximum);
        });

        shape.getMemberTrait(model, LengthTrait.class).ifPresent(t -> {
            // The current shape or target for members dictates how this translates.
            Shape targetShape = shape.asMemberShape()
                    .flatMap(target -> model.getShape(target.getTarget()))
                    .orElse(shape);
            if (targetShape.isListShape() || targetShape.isSetShape()) {
                t.getMin().map(Long::intValue).ifPresent(builder::minItems);
                t.getMax().map(Long::intValue).ifPresent(builder::maxItems);
            } else if (targetShape.isMapShape()) {
                t.getMin().map(Long::intValue).ifPresent(builder::minProperties);
                t.getMax().map(Long::intValue).ifPresent(builder::maxProperties);
            } else {
                t.getMin().ifPresent(builder::minLength);
                t.getMax().ifPresent(builder::maxLength);
            }
        });

        if (shape.hasTrait(UniqueItemsTrait.ID)) {
            builder.uniqueItems(true);
        }

        shape.getTrait(EnumTrait.class)
                .map(EnumTrait::getEnumDefinitionValues)
                .ifPresent(builder::enumValues);

        if (shape.isIntEnumShape() && !converter.getConfig().getDisableIntEnums()) {
            builder.intEnumValues(shape.asIntEnumShape().get().getEnumValues().values());
        }

        if (shape.hasTrait(DefaultTrait.ID) && !converter.getConfig().getDisableDefaultValues()) {
            builder.defaultValue(shape.expectTrait(DefaultTrait.class).toNode());
        }

        if (shape.hasTrait(DeprecatedTrait.ID) && getJsonSchemaVersion() != JsonSchemaVersion.DRAFT07) {
            builder.deprecated(true);
        }

        return builder;
    }

    private Optional<String> descriptionMessage(Shape shape) {
        StringBuilder builder = new StringBuilder();
        shape
                .getTrait(DocumentationTrait.class)
                .ifPresent(trait -> builder.append(trait.getValue()));

        JsonSchemaConfig config = converter.getConfig();
        boolean disableDefaultDeprecatedMessage = config.getDisableDefaultDeprecatedMessage();

        shape
                .getTrait(DeprecatedTrait.class)
                .ifPresent(trait -> {
                    if (!disableDefaultDeprecatedMessage || trait.getMessage().isPresent()
                            || trait.getSince().isPresent()) {
                        builder
                                .append("\n")
                                .append(trait.getDeprecatedDescription(shape.getType()));
                    }
                });

        String description = builder.toString().trim();
        return description.isEmpty() ? Optional.empty() : Optional.of(description);
    }

    /**
     * Builds a schema builder and applied schema mappers.
     *
     * <p>Schema builders created in each visitor method should not build
     * themselves and instead should invoke this method to finalize the
     * builder.
     *
     * @param shape Shape being converted.
     * @param builder Schema being built.
     * @return Returns the built schema.
     */
    private Schema buildSchema(Shape shape, Schema.Builder builder) {
        JsonSchemaMapperContext context = new JsonSchemaMapperContext(model, shape, converter.getConfig());
        for (JsonSchemaMapper mapper : mappers) {
            builder = mapper.updateSchema(context, builder);
        }

        return builder.build();
    }

    private JsonSchemaVersion getJsonSchemaVersion() {
        return converter.getConfig().getJsonSchemaVersion();
    }
}
