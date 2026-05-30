{% macro drop_seeds(seed_tables=[], model_tables=[]) %}
  {% set schema = target.dataset %}

  {% for table in seed_tables | list + model_tables | list %}
    {% set relation = adapter.get_relation(
        database=target.project,
        schema=schema,
        identifier=table
    ) %}
    {% if relation %}
      {% do log("[INFO] Dropping: " ~ schema ~ "." ~ table, info=true) %}
      {% do adapter.drop_relation(relation) %}
    {% else %}
      {% do log("[WARNING] Not found, skipping: " ~ schema ~ "." ~ table, info=true) %}
    {% endif %}
  {% endfor %}
{% endmacro %}
