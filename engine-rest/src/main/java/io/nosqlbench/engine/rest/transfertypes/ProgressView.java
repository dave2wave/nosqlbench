/*
 * Copyright (c) 2022 nosqlbench
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

package io.nosqlbench.engine.rest.transfertypes;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.nosqlbench.engine.api.activityapi.core.progress.CycleMeter;
import io.nosqlbench.engine.api.activityapi.core.progress.ProgressMeter;
import io.nosqlbench.engine.api.activityapi.core.progress.StateCapable;

public class ProgressView {

    private final ProgressMeter progressMeter;

    public ProgressView(ProgressMeter progressMeter) {
        if (progressMeter==null) {
            throw new RuntimeException("Unable to create a view with a null progressMeter");
        }
        this.progressMeter = progressMeter;
    }

    @JsonProperty("summary")
    public String getProgressDetails() {
        return progressMeter.getSummary();
    }

    @JsonProperty("min")
    public double getMin() {
        return progressMeter.getMinValue();
    }

    @JsonProperty("current")
    public double getCurrent() {
        return progressMeter.getCurrentValue();
    }

    @JsonProperty("max")
    public double getMax() {
        return progressMeter.getMaxValue();
    }


    @JsonProperty("recycles_max")
    public double getRecyclesMax() {
        if (progressMeter instanceof CycleMeter cm) {
            return cm.getRecyclesMax();
        } else {
            return Double.NaN;
        }
    }

    @JsonProperty("recycles_current")
    public double getRecyclesCurrent() {
        if (progressMeter instanceof CycleMeter cm) {
            return cm.getRecyclesCurrent();
        } else {
            return Double.NaN;
        }
    }

    @JsonProperty("eta_millis")
    public double getEtaMills() {
        return progressMeter.getProgressETAMillis();
    }

    @JsonProperty("name")
    public String getName() {
        return progressMeter.getProgressName();
    }

    @JsonProperty("completed")
    public double getProgress() {
        return progressMeter.getRatioComplete();
    }

    @JsonProperty("state")
    public String getState() {
        if (progressMeter instanceof StateCapable) {
            return ((StateCapable)progressMeter).getRunState().toString();
        } else {
            return "unknown";
        }
    }


}
