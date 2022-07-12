/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2021 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.containerproxy.service;

import eu.openanalytics.containerproxy.model.runtime.AllowedParametersForUser;
import eu.openanalytics.containerproxy.model.runtime.ProvidedParameters;
import eu.openanalytics.containerproxy.model.spec.ParameterDefinition;
import eu.openanalytics.containerproxy.model.spec.Parameters;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.spec.IProxySpecProvider;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ParametersService {

    @Inject
    private IProxySpecProvider baseSpecProvider;

    private static final Pattern PARAMETER_ID_PATTERN = Pattern.compile("[a-zA-Z\\d_-]*");

    @PostConstruct
    public void init() {
        for (ProxySpec spec : baseSpecProvider.getSpecs()) {
            validateSpec(spec);
        }
    }

    private void validateSpec(ProxySpec spec) {
        if (spec.getParameters() == null) {
            return;
        }

        // Validate Parameter Definitions
        HashSet<String> parameterIds = new HashSet<>();
        for (ParameterDefinition definition : spec.getParameters().getDefinitions()) {
            if (definition.getId() == null) {
                throw new IllegalStateException(String.format("Configuration error: error in parameters of spec '%s', error: id of parameter may not be null", spec.getId()));
            }
            if (parameterIds.contains(definition.getId())) {
                throw new IllegalStateException(String.format("Configuration error: error in parameters of spec '%s', error: duplicate parameter id '%s'", spec.getId(), definition.getId()));
            }
            if (!PARAMETER_ID_PATTERN.matcher(definition.getId()).matches()) {
                throw new IllegalStateException(String.format("Configuration error: error in parameters of spec '%s', error: parameter id '%s' is invalid, id may only exists out of Latin letters, numbers, dash and underscore", spec.getId(), definition.getId()));
            }
            parameterIds.add(definition.getId());
            if (definition.getDisplayName() != null && StringUtils.isBlank(definition.getDisplayName())) {
                throw new IllegalStateException(String.format("Configuration error: error in parameters of spec '%s', error: displayName may not be blank of parameter with id '%s'", spec.getId(), definition.getId()));
            }
            if (definition.getDescription() != null && StringUtils.isBlank(definition.getDescription())) {
                throw new IllegalStateException(String.format("Configuration error: error in parameters of spec '%s', error: description may not be blank of parameter with id '%s'", spec.getId(), definition.getId()));
            }
        }

        // Validate Parameter Value Sets
        int valueSetIdx = 0;
        for (Map<String, List<String>> valueSet : spec.getParameters().getValues()) {
            for (String parameterId : spec.getParameters().getIds()) {
                if (!valueSet.containsKey(parameterId) || valueSet.get(parameterId).isEmpty()) {
                    throw new IllegalStateException(String.format("Configuration error: error in parameters of spec '%s', error: value set %s is missing values for parameter with id '%s'", spec.getId(), valueSetIdx, parameterId));
                }
                List<String> values = valueSet.get(parameterId);
                Set<String> valuesAsSet = new HashSet<>(valueSet.get(parameterId));
                if (values.size() != valuesAsSet.size()) {
                    throw new IllegalStateException(String.format("Configuration error: error in parameters of spec '%s', error: value set %s contains some duplicate values for parameter %s", spec.getId(), valueSetIdx, parameterId));
                }
            }
            if (valueSet.size() != spec.getParameters().getIds().size()) {
                throw new IllegalStateException(String.format("Configuration error: error in parameters of spec '%s', error: value set %s contains values for more parameters than there are defined", spec.getId(), valueSetIdx));
            }
            valueSetIdx++;
        }

    }

    public boolean validateRequest(ProxySpec resolvedSpec, ProvidedParameters providedParameters) throws InvalidParametersException {
        Parameters parameters = resolvedSpec.getParameters();
        if (parameters == null) {
            return false;
        }

        // check if correct number of parameters is provided
        if (providedParameters.size() != parameters.getIds().size()) {
            throw new InvalidParametersException("Invalid number of parameters provided");
        }

        // check if all parameter ids are provided
        for (String parameterId : parameters.getIds()) {
            if (!providedParameters.containsParameter(parameterId)) {
                throw new InvalidParametersException("Missing value for parameter " + parameterId);
            }
        }

        // check if the combination of values is allowed
        for (Map<String, List<String>> valueSet : parameters.getValues()) {
            if (areParametersAllowedByValueSet(valueSet, providedParameters)) {
                return true; // parameters are allowed
            }
        }

        throw new InvalidParametersException("Provided parameter values are not allowed");
    }

    private boolean areParametersAllowedByValueSet(Map<String, List<String>> valueSet, ProvidedParameters providedParameters) {
        for (Map.Entry<String, List<String>> keyWithValues : valueSet.entrySet()) {
            if (!providedParameters.containsParameter(keyWithValues.getKey())) {
                throw new IllegalStateException("Could not find value for key " + keyWithValues.getKey());
            }
            String providedValue = providedParameters.getValue(keyWithValues.getKey());
            if (!keyWithValues.getValue().contains(providedValue)) {
                return false;
            }
        }
        // providedParameters contains an allowed value for every parameter
        return true;
    }

    public AllowedParametersForUser calculateAllowedParametersForUser(ProxySpec proxySpec) {
        Parameters parameters = proxySpec.getParameters();
        if (parameters == null) {
            return new AllowedParametersForUser(new HashMap<>(), new HashSet<>());
        }
        List<String> parameterNames = parameters.getIds();

        // 1. compute a unique (per ParameterName) index for every value
        // mapping of ParameterName to a mapping of an allowed value and its index
        Map<String, Map<String, Integer>> valuesToIndex = new HashMap<>();
        Map<String, List<Pair<Integer, String>>> values = new HashMap<>();
        // for every set of allowed values
        for (Map<String, List<String>> parameterValues : parameters.getValues()) {
            // for every parameter in this set
            for (Map.Entry<String, List<String>> parameterNameToValues : parameterValues.entrySet()) {
                String parameterName = parameterNameToValues.getKey();
                valuesToIndex.computeIfAbsent(parameterName, (k) -> new HashMap<>());
                values.computeIfAbsent(parameterName, (k) -> new ArrayList<>());
                // for every value of this parameter
                for (String value : parameterNameToValues.getValue()) {
                    if (!valuesToIndex.get(parameterName).containsKey(value)) {
                        // add it to allValues if it does not yet exist
                        Integer newIndex = valuesToIndex.get(parameterName).size() + 1;
                        valuesToIndex.get(parameterName).put(value, newIndex);
                        values.get(parameterName).add(Pair.of(newIndex, value));
                    }
                }
            }
        }
        // sort values
        values = values.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        (v) -> v.getValue()
                                .stream().sorted(Comparator.comparingInt(Pair::getKey))
                                .collect(Collectors.toList())));

        // 2. compute the set of allowed values
        HashSet<List<Integer>> allowedCombinations = new HashSet<>();

        // for every value-set
        for (Map<String, List<String>> parameterValues : parameters.getValues()) {
            allowedCombinations.addAll(getAllowedCombinationsForSingleValueSet(parameterNames,
                    parameterValues, valuesToIndex));
        }

        return new AllowedParametersForUser(values, allowedCombinations);

    }

    private List<List<Integer>> getAllowedCombinationsForSingleValueSet(List<String> parameterNames,
                                                                        Map<String, List<String>> parameterValues,
                                                                        Map<String, Map<String, Integer>> valuesToIndex
    ) {
        // start with an empty combination
        List<List<Integer>> newAllowedCombinations = new ArrayList<>();
        newAllowedCombinations.add(new ArrayList<>());

        // for each parameter
        for (String parameterName : parameterNames) {
            // copy the combinations calculated during the previous iteration
            List<List<Integer>> previousAllowedCombinations = new ArrayList<>(newAllowedCombinations);
            newAllowedCombinations = new ArrayList<>();
            // for every allowed value of this parameter
            for (String allowedValue : parameterValues.get(parameterName)) {
                // and for every combination of the previous iteration
                for (List<Integer> combination : previousAllowedCombinations) {
                    // create a new combination with the additional value
                    combination = new ArrayList<>(combination); // copy the previous combination so that we can extend it
                    Integer index = valuesToIndex.get(parameterName).get(allowedValue);
                    combination.add(index);
                    newAllowedCombinations.add(combination);
                }
            }
        }
        return newAllowedCombinations;
    }

}
