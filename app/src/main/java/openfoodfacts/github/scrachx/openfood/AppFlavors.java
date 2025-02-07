/*
 * Copyright 2016-2020 Open Food Facts
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package openfoodfacts.github.scrachx.openfood;

import org.apache.commons.lang.ArrayUtils;

public class AppFlavors {
    public static final String OFF = "off";
    public static final String OPFF = "opff";
    public static final String OPF = "opf";
    public static final String OBF = "obf";

    private AppFlavors() {
    }

    public static boolean isFlavor(String... flavors) {
        return ArrayUtils.contains(flavors, BuildConfig.FLAVOR);
    }
}
