package com.citypass.movilidad.eventbus.consume;

import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/** Arma eventos Avro con el envelope {metadata, data} del bus, como los produce el gateway. */
final class AvroEvents {

    static final Schema METADATA = SchemaBuilder.record("EventMetadata").namespace("com.citypass.gateway").fields()
            .requiredString("eventId")
            .requiredString("eventType")
            .name("receivedAt").type(LogicalTypes.timestampMillis()
                    .addToSchema(Schema.create(Schema.Type.LONG))).noDefault()
            .requiredString("source")
            .requiredString("tokenId")
            .requiredInt("schemaId")
            .requiredString("payloadHash")
            .requiredString("gatewayVersion")
            .requiredString("instanceId")
            .endRecord();

    static final Schema DATA = SchemaBuilder.record("EventoDePruebaData").namespace("com.citypass.test").fields()
            .requiredString("estacionId")
            .requiredInt("cantidad")
            .optionalString("comentario")
            .requiredBoolean("falla")
            .endRecord();

    static final Schema ENVELOPE = SchemaBuilder.record("EventoDePrueba").namespace("com.citypass.test").fields()
            .name("metadata").type(METADATA).noDefault()
            .name("data").type(DATA).noDefault()
            .endRecord();

    private AvroEvents() {
    }

    static GenericRecord envelope(String eventId, String eventType, boolean falla) {
        GenericRecord metadata = new GenericData.Record(METADATA);
        metadata.put("eventId", eventId);
        metadata.put("eventType", eventType);
        metadata.put("receivedAt", 1786547143000L);
        metadata.put("source", "grupo5");
        metadata.put("tokenId", "af2480cc-487f-474f-ac06-f396ad3f403d");
        metadata.put("schemaId", 7);
        metadata.put("payloadHash", "ae9c4096bd582ac95a75c115b5b87ec26bd8df44391a11e7ea6de5243a4cd801");
        metadata.put("gatewayVersion", "0.0.1-SNAPSHOT");
        metadata.put("instanceId", "gw-aee31cc6");

        GenericRecord data = new GenericData.Record(DATA);
        data.put("estacionId", "est-003");
        data.put("cantidad", 4);
        data.put("comentario", null);
        data.put("falla", falla);

        GenericRecord envelope = new GenericData.Record(ENVELOPE);
        envelope.put("metadata", metadata);
        envelope.put("data", data);
        return envelope;
    }
}
