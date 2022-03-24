// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.query.properties;

import com.yahoo.api.annotations.Beta;
import com.yahoo.processing.request.CompoundName;
import com.yahoo.search.query.Properties;
import com.yahoo.search.query.profile.types.FieldDescription;
import com.yahoo.search.query.profile.types.QueryProfileType;
import com.yahoo.search.query.profile.types.QueryProfileTypeRegistry;

import java.util.Map;

/**
 * Default values for properties that are meant to be customized in query profiles.
 * 
 * @author Tony Vaagenes
 */
public final class DefaultProperties extends Properties  {

    public static final CompoundName MAX_OFFSET = new CompoundName("maxOffset");
    public static final CompoundName MAX_HITS = new CompoundName("maxHits");
    @Beta public static final CompoundName GROUPING_GLOBAL_MAX_GROUPS = new CompoundName("grouping.globalMaxGroups");

    public static final QueryProfileType argumentType = new QueryProfileType("DefaultProperties");

    static {
        argumentType.setBuiltin(true);

        argumentType.addField(new FieldDescription(MAX_OFFSET.toString(), "integer"));
        argumentType.addField(new FieldDescription(MAX_HITS.toString(), "integer"));
        argumentType.addField(new FieldDescription(GROUPING_GLOBAL_MAX_GROUPS.toString(), "long"), new QueryProfileTypeRegistry());

        argumentType.freeze();
    }

    @Override
    public Object get(CompoundName name, Map<String, String> context, com.yahoo.processing.request.Properties substitution) {
        if (MAX_OFFSET.equals(name)) {
            return 1000;
        } else if (MAX_HITS.equals(name)) {
            return 400;
        } else if (GROUPING_GLOBAL_MAX_GROUPS.equals(name)) {
            return -1; // TODO Vespa 8: use default from Vespa 8 release notes
        } else {
            return super.get(name, context, substitution);
        }
    }

}
