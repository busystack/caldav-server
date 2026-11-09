package org.busystack.caldav.calendar.parser;

import static net.fortuna.ical4j.model.Property.*;
import static net.fortuna.ical4j.validate.ValidationRule.ValidationType.*;

import java.util.Locale;
import java.util.Set;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.VToDo;
import net.fortuna.ical4j.model.property.EstimatedDuration;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.model.property.Substate;
import net.fortuna.ical4j.validate.*;

/** RFC 10051 task validation composed through the official model/factory/validator APIs. */
final class TaskFactory extends VToDo.Factory {
  // Substate.Factory in this pinned release similarly advertises SUMMARY.
  static final class SubstateFactory extends Content.Factory implements PropertyFactory<Substate> {
    SubstateFactory() {
      super("SUBSTATE");
    }

    @Override
    public Substate createProperty(ParameterList parameters, String value) {
      return new Substate.Factory().createProperty(parameters, value);
    }

    @Override
    public Substate createProperty() {
      return new Substate();
    }
  }

  // The official EstimatedDuration.Factory advertises DURATION rather than its
  // own property name. Correct only the application registry key; delegate parsing.
  static final class EstimatedDurationFactory extends Content.Factory
      implements PropertyFactory<EstimatedDuration> {
    EstimatedDurationFactory() {
      super("ESTIMATED-DURATION");
    }

    @Override
    public EstimatedDuration createProperty(ParameterList parameters, String value) {
      return new EstimatedDuration.Factory().createProperty(parameters, value);
    }

    @Override
    public EstimatedDuration createProperty() {
      return new EstimatedDuration();
    }
  }

  private static final Set<String> TASK_STATUSES =
      Set.of("NEEDS-ACTION", "COMPLETED", "IN-PROCESS", "CANCELLED", "PENDING", "FAILED");

  // RFC 5545 §3.6.2 cardinalities, with RFC 10051's additions. No library rules or
  // validation diagnostics are mutated, removed by message matching, or globally disabled.
  private static final PropertyContainerRuleSet<VToDo> TASK_RULES =
      new PropertyContainerRuleSet<>(
          new ValidationRule<>(One, UID, DTSTAMP),
          new ValidationRule<>(
              OneOrLess,
              CLASS,
              COMPLETED,
              CREATED,
              DESCRIPTION,
              DTSTART,
              GEO,
              LAST_MODIFIED,
              LOCATION,
              ORGANIZER,
              PERCENT_COMPLETE,
              PRIORITY,
              RECURRENCE_ID,
              SEQUENCE,
              STATUS,
              SUMMARY,
              URL,
              "ESTIMATED-DURATION",
              "TASK-MODE"),
          new ValidationRule<>(OneExclusive, DUE, DURATION),
          new ValidationRule<>(OneOrLess, DUE, DURATION),
          new ValidationRule<>(
              t ->
                  t.getProperties(STATUS).stream()
                      .anyMatch(
                          p -> !TASK_STATUSES.contains(p.getValue().toUpperCase(Locale.ROOT))),
              "STATUS value not applicable for VTODO",
              STATUS));

  @Override
  public VToDo createComponent() {
    return new Task(new PropertyList(), new ComponentList<>());
  }

  @Override
  public VToDo createComponent(PropertyList properties) {
    return new Task(properties, new ComponentList<>());
  }

  @Override
  public VToDo createComponent(PropertyList properties, ComponentList<?> children) {
    return new Task(properties, children);
  }

  private static final class Task extends VToDo {
    Task(PropertyList properties, ComponentList<?> children) {
      super(properties);
      setComponentList(new ComponentList<Component>(children.getAll()));
    }

    @Override
    public ValidationResult validate(boolean recurse) {
      var result = new ValidationResult(TASK_RULES.apply(VTODO, this));
      // Validate every original property, with the current STATUS grammar below.
      for (var property : getProperties()) result = result.merge(property.validate());
      ComponentValidator.validateAlarms(this, true, result);
      for (var child : getComponents()) result = result.merge(child.validate(recurse));
      return result;
    }

    @Override
    protected ComponentFactory<VToDo> newFactory() {
      return new TaskFactory();
    }
  }

  static final class StatusFactory extends Status.Factory {
    private static final Validator<Status> CURRENT_STATUS =
        new PropertyValidator<>(
            STATUS,
            new ValidationRule<>(
                ValueMatch,
                "(?i)TENTATIVE|CONFIRMED|CANCELLED|NEEDS-ACTION|COMPLETED|IN-PROCESS|DRAFT|FINAL|PENDING|FAILED"));

    @Override
    public Status createProperty(ParameterList parameters, String value) {
      // Retain upstream parsing/canonicalization for every pre-existing status.
      // Only the two newly registered values require a current validator.
      if (value.equalsIgnoreCase("PENDING") || value.equalsIgnoreCase("FAILED"))
        return new CurrentStatus(parameters, value);
      return super.createProperty(parameters, value);
    }

    private static final class CurrentStatus extends Status {
      CurrentStatus(ParameterList parameters, String value) {
        super(parameters, value);
      }

      @Override
      public ValidationResult validate() {
        return CURRENT_STATUS.validate(this);
      }

      @Override
      protected PropertyFactory<Status> newFactory() {
        return new StatusFactory();
      }
    }
  }
}
