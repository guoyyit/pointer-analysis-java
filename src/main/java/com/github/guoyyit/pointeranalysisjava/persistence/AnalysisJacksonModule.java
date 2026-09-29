package com.github.guoyyit.pointeranalysisjava.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.guoyyit.pointeranalysisjava.model.Context;
import com.github.guoyyit.pointeranalysisjava.model.InstanceFieldId;
import com.github.guoyyit.pointeranalysisjava.model.Obj;
import com.github.guoyyit.pointeranalysisjava.model.Var;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic string-key serialization for model types used as map keys in
 * the persisted snapshot. Format versions are stable and one-to-one so a
 * recovered snapshot is identical to the published one.
 */
final class AnalysisJacksonModule extends SimpleModule {

    AnalysisJacksonModule() {
        addKeySerializer(Var.class, new ToStringKeySerializer());
        addKeySerializer(Obj.class, new ToStringKeySerializer());
        addKeySerializer(InstanceFieldId.class, new ToStringKeySerializer());
        addKeyDeserializer(Var.class, new VarKeyDeserializer());
        addKeyDeserializer(Obj.class, new ObjKeyDeserializer());
        addKeyDeserializer(InstanceFieldId.class, new FieldKeyDeserializer());
    }

    private static final class ToStringKeySerializer extends StdSerializer<Object> {
        ToStringKeySerializer() {
            super(Object.class);
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeFieldName(value.toString());
        }
    }

    static final class VarKeyDeserializer extends KeyDeserializer {
        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            int methodSep = key.indexOf("::");
            int nameSep = key.lastIndexOf('#');
            Context ctx = parseContext(key.substring(0, methodSep));
            String method = key.substring(methodSep + 2, nameSep);
            String name = key.substring(nameSep + 1);
            return new Var(ctx, method, name);
        }
    }

    static final class ObjKeyDeserializer extends KeyDeserializer {
        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            int at = key.lastIndexOf('@');
            return new Obj(key.substring(0, at), parseContext(key.substring(at + 1)));
        }
    }

    static final class FieldKeyDeserializer extends KeyDeserializer {
        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            int lastDot = key.lastIndexOf('.');
            String objText = key.substring(0, lastDot);
            String field = key.substring(lastDot + 1);
            int at = objText.lastIndexOf('@');
            Obj base = new Obj(objText.substring(0, at), parseContext(objText.substring(at + 1)));
            return new InstanceFieldId(base, field);
        }
    }

    private static Context parseContext(String text) {
        if ("<root>".equals(text)) {
            return Context.ROOT;
        }
        List<String> sites = new ArrayList<>();
        for (String part : text.split("/")) {
            if (!part.isEmpty()) {
                sites.add(part);
            }
        }
        return Context.of(sites);
    }
}
