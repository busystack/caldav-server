"""Heap evidence comes from the owned application JVM, never a guessed observation."""
from subprocess import CompletedProcess, CalledProcessError
import unittest
from unittest.mock import patch
import workload_measurement


class HeapEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.environment={'COMPOSE_PROJECT_NAME':'owned-fixture'}
        self.inspected={'Config':{'Labels':{'com.docker.compose.project':'owned-fixture','com.docker.compose.service':'application'},
            'Entrypoint':['java','-XX:MaxRAMPercentage=70.0','-jar','/opt/busystack/application.jar'],'Cmd':None},
            'HostConfig':{'Memory':1073741824}}

    def test_captures_actual_pid_one_max_heap_without_tool_option_inheritance(self):
        with patch('workload_measurement.subprocess.run',return_value=CompletedProcess([],0,'1:\n-XX:InitialHeapSize=16777216 -XX:MaxHeapSize=751619276 -XX:MaxRAMPercentage=70.000000\n','')) as run:
            result=workload_measurement.heap_configuration('owned-app',self.inspected,self.environment)
        self.assertEqual(result['maxHeapBytes'],751619276)
        self.assertEqual(result['source'],'actual owned-container jcmd PID1 VM.flags')
        self.assertIn('JAVA_TOOL_OPTIONS=',run.call_args.args[0])
        self.assertEqual(run.call_args.kwargs['timeout'],15)

    def test_unavailable_attach_records_command_policy_without_fabricating_actual_heap(self):
        with patch('workload_measurement.subprocess.run',side_effect=CalledProcessError(1,['jcmd'])):
            result=workload_measurement.heap_configuration('owned-app',self.inspected,self.environment)
        self.assertIsNone(result['maxHeapBytes'])
        self.assertEqual(result['configuredMaxRAMPercentage'],'70.0')
        self.assertIn('-XX:MaxRAMPercentage=70.0',result['applicationJvmCommand'])

    def test_refuses_to_attach_to_an_unrelated_project(self):
        self.inspected['Config']['Labels']['com.docker.compose.project']='unrelated'
        with patch('workload_measurement.subprocess.run') as run:
            with self.assertRaises(AssertionError):
                workload_measurement.heap_configuration('unrelated-app',self.inspected,self.environment)
        run.assert_not_called()
