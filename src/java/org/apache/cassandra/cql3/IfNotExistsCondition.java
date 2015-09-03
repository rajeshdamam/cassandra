package org.apache.cassandra.cql3;

import org.apache.cassandra.cql3.statements.CQL3CasRequest;
import org.apache.cassandra.db.Clustering;

final class IfNotExistsCondition extends AbstractConditions
{
    @Override
    public void addConditionsTo(CQL3CasRequest request, Clustering clustering, QueryOptions options)
    {
        request.addNotExist(clustering);
    }

    @Override
    public boolean isIfNotExists()
    {
        return true;
    }
}
