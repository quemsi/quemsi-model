package com.quemsi.model.flow.subset;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubsetDriver {
    /** Schema-qualified or bare table name as entered by the user. */
    private String table;
    /** WHERE fragment using alias {@code t}; ignored when {@link #entireTable} is true. */
    private String where;
    /** Optional max seed rows after applying {@link #where}. */
    private Integer limit;
    /** When true, seed all rows of the table (no WHERE). */
    private boolean entireTable;
}
