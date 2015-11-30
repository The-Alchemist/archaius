/**
 * Copyright 2014 Netflix, Inc.
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
package com.netflix.config;

/**
 * A dynamic property whose value is a double. 
 * 
 * <p>Use APIs in {@link DynamicPropertyFactory} to create instance of this class.
 * 
 * @author awang
 *
 */
public class DynamicDoubleProperty extends PropertyWrapper<Double> {

    protected volatile double primitiveValue;

    public DynamicDoubleProperty(String propName, double defaultValue) {
        super(propName, Double.valueOf(defaultValue));

        // Set the initial value of the cached primitive value.
        this.primitiveValue = chooseValue();

        // Add a callback to update the cached primitive value when the property is changed.
        this.prop.addCallback(() -> primitiveValue = chooseValue() );
    }

    /**
     * Get the current value from the underlying DynamicProperty
     *
     * @return
     */
    private double chooseValue() {
        Double propValue = this.prop == null ? null : this.prop.getDouble(defaultValue);
        return propValue == null ? defaultValue : propValue.doubleValue();
    }

    /**
     * Get the current cached value.
     *
     * @return
     */
    public double get() {
        return primitiveValue;
    }

    @Override
    public Double getValue() {
        return get();
    }
}
