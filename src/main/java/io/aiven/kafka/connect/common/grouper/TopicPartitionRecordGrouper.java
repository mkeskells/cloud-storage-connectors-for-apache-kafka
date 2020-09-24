/*
 * Copyright 2020 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.common.grouper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.sink.SinkRecord;

import io.aiven.kafka.connect.common.config.FilenameTemplateVariable;
import io.aiven.kafka.connect.common.config.TimestampSource;
import io.aiven.kafka.connect.common.templating.Template;
import io.aiven.kafka.connect.common.templating.VariableTemplatePart.Parameter;

/**
 * A {@link RecordGrouper} that groups records by topic and partition.
 *
 * <p>The class requires a filename template with {@code topic}, {@code partition},
 * and {@code start_offset} variables declared.
 *
 * <p>The class supports limited and unlimited number of records in files.
 */
public final class TopicPartitionRecordGrouper implements RecordGrouper {

    private final Template filenameTemplate;

    private final Integer maxRecordsPerFile;

    private final Map<TopicPartition, SinkRecord> currentHeadRecords = new HashMap<>();

    private final Map<String, List<SinkRecord>> fileBuffers = new HashMap<>();

    private final Function<Parameter, String> setTimestamp;

    /**
     * A constructor.
     *
     * @param filenameTemplate  the filename template.
     * @param maxRecordsPerFile the maximum number of records per file ({@code null} for unlimited).
     * @param tsSource timestamp sources
     */
    public TopicPartitionRecordGrouper(final Template filenameTemplate,
                                       final Integer maxRecordsPerFile,
                                       final TimestampSource tsSource) {
        Objects.requireNonNull(filenameTemplate, "filenameTemplate cannot be null");
        Objects.requireNonNull(tsSource, "tsSource cannot be null");
        this.filenameTemplate = filenameTemplate;
        this.maxRecordsPerFile = maxRecordsPerFile;
        this.setTimestamp = new Function<Parameter, String>() {

            //FIXME move into commons lib
            private final Map<String, DateTimeFormatter> timestampFormatters =
                Map.of(
                    "YYYY", DateTimeFormatter.ofPattern("YYYY"),
                    "MM", DateTimeFormatter.ofPattern("MM"),
                    "dd", DateTimeFormatter.ofPattern("dd"),
                    "HH", DateTimeFormatter.ofPattern("HH")
                );

            @Override
            public String apply(final Parameter parameter) {
                return tsSource.time().format(timestampFormatters.get(parameter.value()));
            }

        };
    }

    @Override
    public void put(final SinkRecord record) {
        Objects.requireNonNull(record, "record cannot be null");

        final TopicPartition tp = new TopicPartition(record.topic(), record.kafkaPartition());
        final SinkRecord currentHeadRecord = currentHeadRecords.computeIfAbsent(tp, ignored -> record);
        final String recordKey = generateRecordKey(tp, currentHeadRecord);

        if (shouldCreateNewFile(recordKey)) {
            // Create new file using this record as the head record.
            currentHeadRecords.put(tp, record);
            final String newRecordKey = generateRecordKey(tp, record);
            fileBuffers.computeIfAbsent(newRecordKey, ignored -> new ArrayList<>()).add(record);
        } else {
            fileBuffers.computeIfAbsent(recordKey, ignored -> new ArrayList<>()).add(record);
        }
    }

    private String generateRecordKey(final TopicPartition tp, final SinkRecord headRecord) {
        //FIXME move into commons lib
        final Function<Parameter, String> setKafkaOffset =
            usePaddingParameter -> usePaddingParameter.asBoolean()
                ? String.format("%020d", headRecord.kafkaOffset())
                : Long.toString(headRecord.kafkaOffset());

        return filenameTemplate.instance()
            .bindVariable(FilenameTemplateVariable.TOPIC.name, tp::topic)
            .bindVariable(
                FilenameTemplateVariable.PARTITION.name,
                () -> Integer.toString(tp.partition())
            ).bindVariable(
                FilenameTemplateVariable.START_OFFSET.name,
                setKafkaOffset
            ).bindVariable(
                FilenameTemplateVariable.TIMESTAMP.name,
                setTimestamp
            ).render();
    }

    private boolean shouldCreateNewFile(final String recordKey) {
        final boolean unlimited = maxRecordsPerFile == null;
        if (unlimited) {
            return false;
        } else {
            final List<SinkRecord> buffer = fileBuffers.get(recordKey);
            return buffer == null || buffer.size() >= maxRecordsPerFile;
        }
    }

    @Override
    public void clear() {
        currentHeadRecords.clear();
        fileBuffers.clear();
    }

    @Override
    public Map<String, List<SinkRecord>> records() {
        return Collections.unmodifiableMap(fileBuffers);
    }

}
