{% macro check_bq_tables(seed_tables=[], model_tables=[]) %}
  {% set schema = target.dataset %}

  {% for table in seed_tables | list + model_tables | list %}
    {% set relation = adapter.get_relation(
        database=target.project,
        schema=schema,
        identifier=table
    ) %}
    {% if relation %}
      {% do log("[INFO]    Found: " ~ schema ~ "." ~ table, info=true) %}
    {% else %}
      {% do log("[WARNING] Not found: " ~ schema ~ "." ~ table, info=true) %}
    {% endif %}
  {% endfor %}
{% endmacro %}
