package angstromio.validation;

import jakarta.validation.Valid;

import java.util.List;

public record JavaTestClass(List<@Valid String> names) {}
