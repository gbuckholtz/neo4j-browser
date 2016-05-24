import React from 'react'
import asciitable from 'ascii-data-table'
import bolt from '../../../services/bolt'

const CypherFrame = ({frame}) => {
  const errors = frame.errors && frame.errors.fields || false
  const result = frame.result || false
  let frameContents = <pre>{JSON.stringify(result, null, 2)}</pre>
  if (result) {
    const rows = bolt.recordsToTableArray(result.records)
    frameContents = <pre>{asciitable.run(rows)}</pre>
  } else if (errors) {
    frameContents = (
      <div>
        {errors[0].code}
        <pre>{errors[0].message}</pre>
      </div>
    )
  }
  return <div className='frame'>{frameContents}</div>
}

export {
  CypherFrame
}