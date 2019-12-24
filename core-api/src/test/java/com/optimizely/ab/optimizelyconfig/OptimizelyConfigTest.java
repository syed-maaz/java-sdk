/****************************************************************************
 * Copyright 2019, Optimizely, Inc. and contributors                        *
 *                                                                          *
 * Licensed under the Apache License, Version 2.0 (the "License");          *
 * you may not use this file except in compliance with the License.         *
 * You may obtain a copy of the License at                                  *
 *                                                                          *
 *    http://www.apache.org/licenses/LICENSE-2.0                            *
 *                                                                          *
 * Unless required by applicable law or agreed to in writing, software      *
 * distributed under the License is distributed on an "AS IS" BASIS,        *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. *
 * See the License for the specific language governing permissions and      *
 * limitations under the License.                                           *
 ***************************************************************************/
package com.optimizely.ab.optimizelyconfig;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import static com.optimizely.ab.optimizelyconfig.OptimizelyExperimentTest.generateVariationMap;
import static com.optimizely.ab.optimizelyconfig.OptimizelyVariationTest.generateVariablesMap;
import static org.junit.Assert.assertEquals;

public class OptimizelyConfigTest {

    @Test
    public void testOptimizelyConfig() {
        OptimizelyConfig optimizelyConfig = new OptimizelyConfig(
            generateExperimntMap(),
            generateFeatureMap(),
            "101"
        );
        assertEquals("101", optimizelyConfig.getRevision());
        // verify the experiments map
        Map<String, OptimizelyExperiment> optimizelyExperimentMap = generateExperimntMap();
        assertEquals(optimizelyExperimentMap.size(), optimizelyConfig.getExperimentsMap().size());
        assertEquals(optimizelyExperimentMap, optimizelyConfig.getExperimentsMap());

        // verify the features map
        Map<String, OptimizelyFeature> optimizelyFeatureMap = generateFeatureMap();
        assertEquals(optimizelyFeatureMap.size(), optimizelyConfig.getFeaturesMap().size());
        assertEquals(optimizelyFeatureMap, optimizelyConfig.getFeaturesMap());
    }

    private Map<String, OptimizelyExperiment> generateExperimntMap() {
        Map<String, OptimizelyExperiment> optimizelyExperimentMap = new HashMap<>();
        optimizelyExperimentMap.put("test_exp_1", new OptimizelyExperiment(
            "33",
            "test_exp_1",
            generateVariationMap()
        ));
        optimizelyExperimentMap.put("test_exp_2", new OptimizelyExperiment(
            "34",
            "test_exp_2",
            generateVariationMap()
        ));
        return optimizelyExperimentMap;
    }

    private Map<String, OptimizelyFeature> generateFeatureMap() {
        Map<String, OptimizelyFeature> optimizelyFeatureMap = new HashMap<>();
        optimizelyFeatureMap.put("test_feature_1", new OptimizelyFeature(
           "42",
           "test_feature_1",
            generateExperimntMap(),
            generateVariablesMap()
        ));
        return  optimizelyFeatureMap;
    }
}
