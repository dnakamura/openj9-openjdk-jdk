/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.util.cldr;

import static sun.util.locale.provider.LocaleProviderAdapter.Type;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;
import sun.util.calendar.ZoneInfoFile;
import sun.util.locale.provider.LocaleProviderAdapter;
import sun.util.locale.provider.LocaleResources;
import sun.util.locale.provider.TimeZoneNameProviderImpl;
import sun.util.locale.provider.TimeZoneNameUtility;

/**
 * Concrete implementation of the
 * {@link java.util.spi.TimeZoneNameProvider TimeZoneNameProvider} class
 * for the CLDR LocaleProviderAdapter.
 *
 * @author Naoto Sato
 */
public class CLDRTimeZoneNameProviderImpl extends TimeZoneNameProviderImpl {

    private static final String NO_INHERITANCE_MARKER = "\u2205\u2205\u2205";
    private static class AVAILABLE_IDS {
        static final String[] INSTANCE =
            Arrays.stream(ZoneInfoFile.getZoneIds())
                .sorted()
                .toArray(String[]::new);
    }

    // display name array indexes
    private static final int INDEX_TZID         = 0;
    private static final int INDEX_STD_LONG     = 1;
    private static final int INDEX_STD_SHORT    = 2;
    private static final int INDEX_DST_LONG     = 3;
    private static final int INDEX_DST_SHORT    = 4;
    private static final int INDEX_GEN_LONG     = 5;
    private static final int INDEX_GEN_SHORT    = 6;

    public CLDRTimeZoneNameProviderImpl(Type type, Set<String> langtags) {
        super(type, langtags);
    }

    @Override
    protected String[] getDisplayNameArray(String id, Locale locale) {
        String tzid = TimeZoneNameUtility.canonicalTZID(id).orElse(id);
        String[] namesSuper = super.getDisplayNameArray(tzid, locale);

        if (Objects.nonNull(namesSuper)) {
            // CLDR's resource bundle has an translated entry for this id.
            // Fix up names if needed, either missing or no-inheritance
            namesSuper[INDEX_TZID] = id;

            // Check if standard long name exists. If not, try to retrieve the name
            // from language only locale resources. E.g., "Europe/London"
            // for en-GB only contains DST names
            if (!exists(namesSuper, INDEX_STD_LONG) && !locale.getCountry().isEmpty()) {
                String[] names =
                        getDisplayNameArray(id, Locale.forLanguageTag(locale.getLanguage()));
                if (exists(names, INDEX_STD_LONG)) {
                    namesSuper[INDEX_STD_LONG] = names[INDEX_STD_LONG];
                }
            }

            for(int i = INDEX_STD_LONG; i < namesSuper.length; i++) { // index 0 is the 'id' itself
                switch (namesSuper[i]) {
                case "":
                    // Fill in empty elements
                    deriveFallbackName(namesSuper, i, locale,
                            namesSuper[INDEX_DST_LONG].isEmpty());
                    break;
                case NO_INHERITANCE_MARKER:
                    // CLDR's "no inheritance marker"
                    namesSuper[i] = toGMTFormat(id, i == INDEX_DST_LONG || i == INDEX_DST_SHORT,
                                                i % 2 != 0, locale);
                    break;
                default:
                    break;
                }
            }
            return namesSuper;
        } else {
            // Derive the names for this id. Validate the id first.
            if (Arrays.binarySearch(AVAILABLE_IDS.INSTANCE, id) >= 0) {
                String[] names = new String[INDEX_GEN_SHORT + 1];
                names[INDEX_TZID] = id;
                deriveFallbackNames(names, locale);
                return names;
            }
        }

        return null;
    }

    @Override
    protected String[][] getZoneStrings(Locale locale) {
        // Use English for the ROOT locale
        locale = locale.equals(Locale.ROOT) ? Locale.ENGLISH : locale;
        String[][] ret = super.getZoneStrings(locale);

        // Fill in for the empty names.
        // English names are prefilled for performance.
        if (locale.getLanguage() != "en") {
            for (int zoneIndex = 0; zoneIndex < ret.length; zoneIndex++) {
                deriveFallbackNames(ret[zoneIndex], locale);
            }
        }
        return ret;
    }

    // Derive fallback time zone name according to LDML's logic
    private void deriveFallbackNames(String[] names, Locale locale) {
        for (int i = INDEX_STD_LONG; i <= INDEX_GEN_SHORT; i++) {
            deriveFallbackName(names, i, locale, false);
        }
    }

    private void deriveFallbackName(String[] names, int index, Locale locale, boolean noDST) {
        if (exists(names, index)) {
            return;
        }

        // Check if COMPAT can substitute the name
        if (LocaleProviderAdapter.getAdapterPreference().contains(Type.JRE)) {
            String[] compatNames = (String[])LocaleProviderAdapter.forJRE()
                .getLocaleResources(locale)
                .getTimeZoneNames(names[INDEX_TZID]);
            if (compatNames != null) {
                for (int i = INDEX_STD_LONG; i <= INDEX_GEN_SHORT; i++) {
                    // Assumes COMPAT has no empty slots
                    if (i == index || !exists(names, i)) {
                        names[i] = compatNames[i];
                    }
                }
                return;
            }
        }

        // Type Fallback
        if (noDST && typeFallback(names, index)) {
            return;
        }

        // Region Fallback
        if (regionFormatFallback(names, index, locale)) {
            return;
        }

        // last resort
        String id = names[INDEX_TZID].toUpperCase(Locale.ROOT);
        if (!id.startsWith("ETC/GMT") &&
                !id.startsWith("GMT") &&
                !id.startsWith("UT")) {
            names[index] = toGMTFormat(names[INDEX_TZID],
                                       index == INDEX_DST_LONG || index == INDEX_DST_SHORT,
                                       index % 2 != 0,
                                       locale);
        }
    }

    private boolean exists(String[] names, int index) {
        return Objects.nonNull(names)
                && Objects.nonNull(names[index])
                && !names[index].isEmpty();
    }

    private boolean typeFallback(String[] names, int index) {
        // check generic
        int genIndex = INDEX_GEN_SHORT - index % 2;
        if (!exists(names, index) && exists(names, genIndex)) {
            names[index] = names[genIndex];
        } else {
            // check standard
            int stdIndex = INDEX_STD_SHORT - index % 2;
            if (!exists(names, index) && exists(names, stdIndex)) {
                names[index] = names[stdIndex];
            }
        }

        return exists(names, index);
    }

    private boolean regionFormatFallback(String[] names, int index, Locale l) {
        String id = names[INDEX_TZID];
        LocaleResources lr = LocaleProviderAdapter.forType(Type.CLDR).getLocaleResources(l);
        ResourceBundle fd = lr.getJavaTimeFormatData();

        String rgn = (String) lr.getTimeZoneNames("timezone.excity." + id);
        if (rgn == null && !id.startsWith("Etc") && !id.startsWith("SystemV")) {
            int slash = id.lastIndexOf('/');
            if (slash > 0) {
                rgn = id.substring(slash + 1).replaceAll("_", " ");
            }
        }

        if (rgn != null) {
            String fmt = "";
            switch (index) {
            case INDEX_STD_LONG:
                fmt = fd.getString("timezone.regionFormat.standard");
                break;
            case INDEX_DST_LONG:
                fmt = fd.getString("timezone.regionFormat.daylight");
                break;
            case INDEX_GEN_LONG:
                fmt = fd.getString("timezone.regionFormat");
                break;
            }
            if (!fmt.isEmpty()) {
                names[index] = MessageFormat.format(fmt, rgn);
            }
        }

        return exists(names, index);
    }

    private String toGMTFormat(String id, boolean daylight, boolean isShort, Locale l) {
        TimeZone tz = ZoneInfoFile.getZoneInfo(id);
        int offset = (tz.getRawOffset() + (daylight ? tz.getDSTSavings() : 0)) / 60000;
        LocaleResources lr = LocaleProviderAdapter.forType(Type.CLDR).getLocaleResources(l);
        ResourceBundle fd = lr.getJavaTimeFormatData();

        if (offset == 0) {
            return fd.getString("timezone.gmtZeroFormat");
        } else {
            String gmtFormat = fd.getString("timezone.gmtFormat");
            String hourFormat = fd.getString("timezone.hourFormat");

            if (offset > 0) {
                hourFormat = hourFormat.substring(0, hourFormat.indexOf(";"));
            } else {
                hourFormat = hourFormat.substring(hourFormat.indexOf(";") + 1);
                offset = -offset;
            }
            hourFormat = hourFormat
                .replaceFirst("H+", (isShort ? "\\%1\\$d" : "\\%1\\$02d"))
                .replaceFirst("m+", "\\%2\\$02d");
            return MessageFormat.format(gmtFormat,
                    String.format(hourFormat, offset / 60, offset % 60));
        }
    }
}
