
/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

#include "sspace.h"
#include "../thread/collector.h"
#include "../thread/collector_alloc.h"
#include "../common/gc_metadata.h"
#include "../finalizer_weakref/finalizer_weakref.h"

#ifdef GC_GEN_STATS
#include "../gen/gen_stats.h"
#endif

#ifdef MARK_BIT_FLIPPING

static FORCE_INLINE void scan_slot(Collector *collector, REF *p_ref)
{
  if(read_slot(p_ref) == NULL) return;
  
  collector_tracestack_push(collector, p_ref); 
  return;
}

static FORCE_INLINE void scan_object(Collector* collector, Partial_Reveal_Object *p_obj) 
{
  assert((((POINTER_SIZE_INT)p_obj) % GC_OBJECT_ALIGNMENT) == 0);
    
  if (!object_has_ref_field_before_scan(p_obj)) return;
    
  REF *p_ref;

  if (object_is_array(p_obj)) {   /* scan array object */
  
    Partial_Reveal_Array* array = (Partial_Reveal_Array*)p_obj;
    unsigned int array_length = array->array_len; 
    p_ref = (REF *)((POINTER_SIZE_INT)array + (int)array_first_element_offset(array));

    for (unsigned int i = 0; i < array_length; i++) {
      scan_slot(collector, p_ref+i);
    }   

  }else{ /* scan non-array object */
    
    unsigned int num_refs = object_ref_field_num(p_obj);
    int* ref_iterator = object_ref_iterator_init(p_obj);
 
    for(unsigned int i=0; i<num_refs; i++){  
      p_ref = object_ref_iterator_get(ref_iterator+i, p_obj);  
      scan_slot(collector, p_ref);
    }    

#ifndef BUILD_IN_REFERENT
    scan_weak_reference(collector, p_obj, scan_slot);
#endif
  
  }

  return;
}

/* NOTE:: At this point, p_ref can be in anywhere like root, and other spaces, but *p_ref must be in fspace, 
   since only slot which points to object in fspace could be added into TraceStack.
   The problem is the *p_ref may be forwarded already so that, when we come here we find it's pointing to tospace.
   We will simply return for that case. It might be forwarded due to:
    1. two difference slots containing same reference; 
    2. duplicate slots in remset ( we use SSB for remset, no duplication filtering.)
   The same object can be traced by the thread itself, or by other thread.
*/

static FORCE_INLINE void forward_object(Collector* collector, REF *p_ref) 
{
  GC* gc = collector->gc;
  Partial_Reveal_Object *p_obj = read_slot(p_ref);

  if(obj_belongs_to_tospace(p_obj)) return;
    
  if(!obj_belongs_to_nos(p_obj)){
    if(obj_mark_in_oi(p_obj)){
#ifdef GC_GEN_STATS
      if(gc_profile){
        GC_Gen_Collector_Stats* stats = (GC_Gen_Collector_Stats*)collector->stats;
        gc_gen_collector_update_marked_nonnos_obj_stats_minor(stats);
      }
#endif
      scan_object(collector, p_obj);
    }
    return;
  }

  Partial_Reveal_Object* p_target_obj = NULL;
  /* Fastpath: object has already been forwarded, update the ref slot */
  if(obj_is_fw_in_oi(p_obj)) {
    p_target_obj = obj_get_fw_in_oi(p_obj);
    assert(p_target_obj);
    write_slot(p_ref, p_target_obj);
    return;
  }

  /* following is the logic for forwarding */  
  p_target_obj = collector_forward_object(collector, p_obj);
  
  /* if p_target_obj is NULL, it is forwarded by other thread. 
      We can implement the collector_forward_object() so that the forwarding pointer 
      is set in the atomic instruction, which requires to roll back the mos_alloced
      space. That is easy for thread local block allocation cancellation. */
  if( p_target_obj == NULL ){
    if(collector->result == FALSE ){
      /* failed to forward, let's get back to controller. */
      vector_stack_clear(collector->trace_stack);
      return;
    }

    p_target_obj = obj_get_fw_in_oi(p_obj);
    assert(p_target_obj);
    write_slot(p_ref, p_target_obj);
    return;
  }
  /* otherwise, we successfully forwarded */

#ifdef GC_GEN_STATS
  if(gc_profile){
    GC_Gen_Collector_Stats* stats = (GC_Gen_Collector_Stats*)collector->stats;
    gc_gen_collector_update_marked_nos_obj_stats_minor(stats);
    gc_gen_collector_update_moved_nos_obj_stats_minor(stats, vm_object_size(p_obj));
  }
#endif
  write_slot(p_ref, p_target_obj);

  scan_object(collector, p_target_obj); 
  return;
}

static void trace_object(Collector *collector, REF *p_ref)
{ 
  forward_object(collector, p_ref);

  Vector_Block* trace_stack = (Vector_Block*)collector->trace_stack;
  while( !vector_stack_is_empty(trace_stack)){
    p_ref = (REF *)vector_stack_pop(trace_stack); 
#ifdef PREFETCH_SUPPORTED
    /* DO PREFETCH */
   if(mark_prefetch) {
     if(!vector_stack_is_empty(trace_stack)) {
        REF *pref = (REF*)vector_stack_read(trace_stack, 0);
        PREFETCH( read_slot(pref) );
     }
   }
#endif    
    forward_object(collector, p_ref);
    trace_stack = (Vector_Block*)collector->trace_stack;
  }
  return; 
}
 
/* for tracing phase termination detection */
static volatile unsigned int num_finished_collectors = 0;

static void collector_trace_rootsets(Collector* collector)
{
  GC* gc = collector->gc;
  GC_Metadata* metadata = gc->metadata;
#ifdef GC_GEN_STATS
  GC_Gen_Collector_Stats* stats = (GC_Gen_Collector_Stats*)collector->stats;
#endif
  
  unsigned int num_active_collectors = gc->num_active_collectors;
  atomic_cas32( &num_finished_collectors, 0, num_active_collectors);

  Space* space = collector->collect_space;
  collector->trace_stack = free_task_pool_get_entry(metadata);

  /* find root slots saved by 1. active mutators, 2. exited mutators, 3. last cycle collectors */  
  Vector_Block* root_set = pool_iterator_next(metadata->gc_rootset_pool);

  /* first step: copy all root objects to trace tasks. */ 

  TRACE2("gc.process", "GC: collector["<<((POINTER_SIZE_INT)collector->thread_handle)<<"]: copy root objects to trace stack ...");
  while(root_set){
    POINTER_SIZE_INT* iter = vector_block_iterator_init(root_set);
    while(!vector_block_iterator_end(root_set,iter)){
      REF *p_ref = (REF *)*iter;
      iter = vector_block_iterator_advance(root_set, iter);

      assert(*p_ref);  /* root ref cann't be NULL, but remset can be */

      collector_tracestack_push(collector, p_ref);

#ifdef GC_GEN_STATS    
      gc_gen_collector_update_rootset_ref_num(stats);
#endif
    } 
    root_set = pool_iterator_next(metadata->gc_rootset_pool);
  }
  /* put back the last trace_stack task */    
  pool_put_entry(metadata->mark_task_pool, collector->trace_stack);
  
  /* second step: iterate over the trace tasks and forward objects */
  collector->trace_stack = free_task_pool_get_entry(metadata);

  TRACE2("gc.process", "GC: collector["<<((POINTER_SIZE_INT)collector->thread_handle)<<"]: finish copying root objects to trace stack.");

  TRACE2("gc.process", "GC: collector["<<((POINTER_SIZE_INT)collector->thread_handle)<<"]: trace and forward objects ...");

retry:
  Vector_Block* trace_task = pool_get_entry(metadata->mark_task_pool);

  while(trace_task){    
    POINTER_SIZE_INT* iter = vector_block_iterator_init(trace_task);
    while(!vector_block_iterator_end(trace_task,iter)){
      REF *p_ref = (REF *)*iter;
      iter = vector_block_iterator_advance(trace_task, iter);
#ifdef PREFETCH_SUPPORTED
      /* DO PREFETCH */
      if( mark_prefetch ) {    
        if(!vector_block_iterator_end(trace_task, iter)) {
      	  REF *pref= (REF*) *iter;
      	  PREFETCH( read_slot(pref));
        }	
      }
#endif      
      trace_object(collector, p_ref);
      
      if(collector->result == FALSE)  break; /* force return */
 
    }
    vector_stack_clear(trace_task);
    pool_put_entry(metadata->free_task_pool, trace_task);

    if(collector->result == FALSE){
      gc_task_pool_clear(metadata->mark_task_pool);
      break; /* force return */
    }
    
    trace_task = pool_get_entry(metadata->mark_task_pool);
  }
  
  /* A collector comes here when seeing an empty mark_task_pool. The last collector will ensure 
     all the tasks are finished.*/
     
  atomic_inc32(&num_finished_collectors);
  while(num_finished_collectors != num_active_collectors){
    if( pool_is_empty(metadata->mark_task_pool)) continue;
    /* we can't grab the task here, because of a race condition. If we grab the task, 
       and the pool is empty, other threads may fall to this barrier and then pass. */
    atomic_dec32(&num_finished_collectors);
    goto retry; 
  }

  TRACE2("gc.process", "GC: collector["<<((POINTER_SIZE_INT)collector->thread_handle)<<"]: finish tracing and forwarding objects.");

  /* now we are done, but each collector has a private stack that is empty */  
  trace_task = (Vector_Block*)collector->trace_stack;
  vector_stack_clear(trace_task);
  pool_put_entry(metadata->free_task_pool, trace_task);   
  collector->trace_stack = NULL;
  
  return;
}

void nongen_ss_pool(Collector* collector) 
{  
  GC* gc = collector->gc;

  Sspace* sspace = (Sspace*)collector->collect_space;
  unsigned int sspace_first_idx = sspace->first_block_idx;
  tospace_start = (void*)&(sspace->blocks[sspace->tospace_first_idx - sspace_first_idx]);
  tospace_end = (void*)&(sspace->blocks[sspace->ceiling_block_idx - sspace_first_idx + 1]);
  
  collector_trace_rootsets(collector);  
  /* the rest work is not enough for parallelization, so let only one thread go */
  if( (POINTER_SIZE_INT)collector->thread_handle != 0 ) {
    TRACE2("gc.process", "GC: collector["<<(POINTER_SIZE_INT)collector->thread_handle<<"] finished");
    return;
  }
  gc->collect_result = gc_collection_result(gc);
  if(!gc->collect_result){
#ifndef BUILD_IN_REFERENT
    fallback_finref_cleanup(gc);
#endif
    return;
  }

  if(!IGNORE_FINREF ){
    collector_identify_finref(collector);
    if(!gc->collect_result) return;
  }
#ifndef BUILD_IN_REFERENT
  else {
      gc_set_weakref_sets(gc);
      gc_update_weakref_ignore_finref(gc);
  }
#endif
  gc_identify_dead_weak_roots(gc);
  
  gc_fix_rootset(collector, FALSE);
  
  TRACE2("gc.process", "GC: collector[0] finished");

  return;
  
}

void trace_obj_in_nongen_ss(Collector *collector, void *p_ref)
{
  trace_object(collector, (REF*)p_ref);
}

#endif /* MARK_BIT_FLIPPING */
