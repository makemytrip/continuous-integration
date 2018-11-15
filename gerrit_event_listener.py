import json, logging, datetime, sys

from pprint import pprint,pformat

from pygerrit.client import GerritClient
from pygerrit2.rest import GerritRestAPI

from requests.auth import HTTPDigestAuth
from elasticsearch import Elasticsearch

import re
from jira import JIRA

logging.basicConfig(filename='log/gerrit_listener.log', filemode='w', level=logging.DEBUG, format='%(asctime)s %(name)s %(levelname)s %(lineno)d:%(message)s',  datefmt='%m/%d/%Y %I:%M:%S')
log = logging.getLogger("gerrit_listener")
logging.getLogger("paramiko").setLevel(logging.WARNING)
logging.getLogger("root").setLevel(logging.DEBUG)
logging.getLogger("urllib3").setLevel(logging.ERROR)
logging.getLogger("elasticsearch").setLevel(logging.ERROR)

class DGerritHandler():

    def __init__(self, gerrit_host="gerrit.mmt.com"):
        self.client = None
        self.ELK_HOST = "127.0.0.1:9200" # elastic search
        self.index_name = datetime.datetime.now().strftime('gerrit-stats-%Y-%m')

        url = "http://127.0.0.1:8080" # gerrit servers
        auth = HTTPDigestAuth('admin', 'pass')
        self.rest_client = GerritRestAPI(url=url, auth=auth)

        # establish connection with jira
        self.jira = JIRA(basic_auth=('jira', 'pass'), options = {'server': '127.0.0.1'}) # Jira server
        self.regex = r'([A-Z]+-[0-9]+)'

        log.info("creating a new connection with %s" % (gerrit_host))
        self.client = GerritClient(gerrit_host)
        log.info("Gerrit version is %s" % (self.client.gerrit_version()))
        self.start_event_stream()

    def start_event_stream(self):
        # start listening to event stream
        log.info("initiating listening to event stream")
        self.client.start_event_stream()

    def event_listen(self):

        iter = 0
        while True:
            try:
                elog = {}
                event = self.client.get_event()
                log.info("==============START=====================================")
                log.info("got a new event %s -- %s" % (event.name, type(event.json)))
                log.info("actual event is %s" % pformat(event.json))
                elog['type'] = event.name
		if event.name == "error-event":
			log.info("got an error-event, exiting the script.............")
			sys.exit()

                elog['gerrit_id'] = event.change.number
                log.info(dir(event))
                if hasattr(event, 'author'):
                    elog['author_username'] = event.author.username if hasattr(event, 'author') else None 
                    elog['author_email'] = event.author.email if hasattr(event, 'author') else None
                elif hasattr(event, 'patchset'):
                    elog['author_username'] = event.patchset.author.username if hasattr(event.patchset, 'author') else None
                    elog['author_email'] = event.patchset.author.email if hasattr(event.patchset, 'author') else None

                elog['project'] = event.change.project
                elog['owner'] = event.change.owner.username
                elog['branch'] = event.change.branch
                elog['patchset_number'] = event.patchset.number
                elog['patchset_size_deletions'] = event.json.get('patchSet').get('sizeDeletions')
                elog['patchset_size_insertions'] = event.json.get('patchSet').get('sizeInsertions')
                elog['subject'] = event.change.subject

                if event.name == 'reviewer-added':
                    elog['reviewers'] = event.reviewer.username

                elif event.name == 'change-merged':
                    elog['submitter'] = event.submitter.username

                elif event.name == 'comment-added' and 'approvals' in event.json.keys():
                    for i in event.json.get('approvals'):
                        log.info(i)
                        if 'oldValue' in i:
                            log.info("----found old value----")
                            elog['approval_approver'] = event.author.username
                            elog['approver_type'] = i.get('type')
                            elog['approval_value'] = i.get('value')
                            elog['approval_description'] = i.get('description')
                            break
                        else:
                            elog['approval_approver'] = event.author.username
                            elog['approver_type'] = 'comment-added'
                            elog['approval_value'] = '0'
                            elog['approval_description'] = 'comment-added'

                log.info("~~~~~~~~~~~~~~~~~~~ Start JIRA Analysis ~~~~~~~~~~~~~~~~~~~")
                issue_type = []
                elog['issue_id'] = re.findall(self.regex, elog['subject'])
                for issue in elog['issue_id']:
                    issue_type.append(self.jira.issue(issue).fields.issuetype.name)

                elog['issue_id_type'] = issue_type
                log.info("~~~~~~~~~~~~~~~~~~~ End JIRA Analysis ~~~~~~~~~~~~~~~~~~~")

                log.info("~~~~~~~~~~~~~~~~~~~ Start Gerrit File Actions ~~~~~~~~~~~~~~~~~~~")
                files_info = {
                    "ADDED": {
                      "lines_added": 0,
                      "lines_removed": 0,
                      "count": 0
                    },
                    "MODIFIED": {
                      "lines_added": 0,
                      "lines_removed": 0,
                      "count": 0
                    },
                    "DELETED": {
                      "lines_added": 0,
                      "lines_removed": 0,
                      "count": 0
                    },
                    "RENAMED": {
                      "lines_added": 0,
                      "lines_removed": 0,
                      "count": 0
                    },
                    "COPIED": {
                      "lines_added": 0,
                      "lines_removed": 0,
                      "count": 0
                    },
                    "REWRITE": {
                      "lines_added": 0,
                      "lines_removed": 0,
                      "count": 0
                    }
                }
                query_result = self.client.run_command("query --current-patch-set --format JSON --files change:{}".format(elog['gerrit_id']))
                output = query_result.stdout.read()
                output = output.split('\n')
                files = json.loads(output[0])
                log.info(elog['project'])
                for file in files['currentPatchSet']['files']:
                    if file['file'] not in ['/COMMIT_MSG', '/MERGE_LIST']:
                        files_info[file['type']]['lines_added'] += file['insertions']
                        files_info[file['type']]['lines_removed'] += file['deletions']
                        files_info[file['type']]['count'] += 1

                elog['files'] = files_info
                log.info("~~~~~~~~~~~~~~~~~~~ End Gerrit File Actions ~~~~~~~~~~~~~~~~~~~")

                log.info("elk message %d is %s" % (iter, json.dumps(elog, indent=2)))
                log.info("==============END=====================================")

                self.log_to_elk(elog)
            except Exception as e:
                log.exception(e)
                if event:
                    log.info(str(event.json))
            finally:
                iter += 1

    def stop_event_stream(self):
        # stop listening to gerrit event stream
        log.info("stop listening to event stream")
        self.client.stop_event_stream()

    def log_to_elk(self, log):
        log['timestamp'] = datetime.datetime.now()
        elk = Elasticsearch(self.ELK_HOST)
        elk.index(index=self.index_name, doc_type='gerrit_info', body=log)

    def get_reviewer_list(self, change_id):

        endpoint = "/changes/%s/reviewers/" % change_id
        data = self.rest_client.get(endpoint)
        reviewers = []
        for i in data:
            if i.get('username') is not None:
                reviewers.append(i.get('username'))

        return reviewers

if __name__ == "__main__":
    gerrit_handler = DGerritHandler()
    gerrit_handler.event_listen()
    gerrit_handler.stop_event_stream()
